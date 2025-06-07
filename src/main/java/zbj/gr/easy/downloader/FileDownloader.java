package zbj.gr.easy.downloader;


import cn.hutool.core.io.FileUtil;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FileDownloader {

    private final String url;
    private final String savePath;
    private final String proxyHost;
    private final int proxyPort;
    private final int threadCount;
    private final CloseableHttpClient httpClient;
    private long startTime; // 新增成员变量
    private static final Logger logger = LoggerFactory.getLogger(FileDownloader.class);

    private FileDownloader(Builder builder) {
        this.url = builder.url;
        this.savePath = builder.savePath != null ? builder.savePath : extractFileNameFromUrl(builder.url);
        this.proxyHost = builder.proxyHost;
        this.proxyPort = builder.proxyPort;
        this.threadCount = builder.threadCount;
        this.httpClient = builder.httpClient;
    }

    // 新增静态方法，用于获取 Builder 实例
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String url;
        private String savePath;
        private String proxyHost;
        private int proxyPort = 0;
        private int threadCount = 8;
        private CloseableHttpClient httpClient;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder savePath(String savePath) {
            this.savePath = savePath;
            return this;
        }

        public Builder proxy(String host, int port) {
            this.proxyHost = host;
            this.proxyPort = port;
            return this;
        }

        public Builder threadCount(int count) {
            this.threadCount = count;
            return this;
        }

        public FileDownloader build() {
            // 创建HttpClient
            if (proxyHost != null && proxyPort > 0) {
                httpClient = DownloadTask.createHttpClientWithProxy(proxyHost, proxyPort);
            } else {
                httpClient = HttpClients.createDefault();
            }
            return new FileDownloader(this);
        }
    }

    public void download() {
        startTime = System.currentTimeMillis(); // 记录开始时间
        try {
            HttpGet request = new HttpGet(url);
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(5000)    // 连接超时（毫秒）
                    .setSocketTimeout(10000)    // Socket 超时（毫秒）
                    .build();
            request.setConfig(requestConfig);
            HttpResponse response = httpClient.execute(request);
            long fileSize = response.getEntity().getContentLength();
            if (fileSize == -1) {
                throw new IOException("Failed to get file size");
            }

            // 初始化保存路径和进度目录
            File outputFile = new File(savePath);
            File progressDir = new File("download_progress/" + getFileName(url));
            if (!progressDir.exists()) {
                progressDir.mkdirs();
            }

            // 检查线程数是否变化，清理旧数据
            checkAndCleanThreadCount(progressDir, outputFile);

            // 分配下载任务
            ExecutorService downloadExecutor = Executors.newFixedThreadPool(threadCount);
            List<DownloadTask> tasks = new ArrayList<>();
            long chunkSize = fileSize / threadCount;
            for (int i = 0; i < threadCount; i++) {
                long start = i * chunkSize;
                long end = (i == threadCount - 1) ? fileSize - 1 : (i + 1) * chunkSize - 1;
                File progressFile = new File(progressDir, "block_" + i + ".progress");
                DownloadTask task = new DownloadTask(
                        httpClient,
                        url,
                        new RandomAccessFile(outputFile, "rw"),
                        start,
                        end,
                        progressFile
                );
                tasks.add(task);
                downloadExecutor.submit(task);
            }

            // 启动进度监控
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                long total = tasks.stream().mapToLong(DownloadTask::getDownloadedBytes).sum();
                long incrementTotal = tasks.stream().mapToLong(DownloadTask::getIncrementDownloadBytes).sum();
                double percent = (total / (double) fileSize) * 100;
                String progress = String.format("%.2f%%", percent);
                logger.info("Downloading {} → {}/{} ({}), Speed: {}",
                        savePath,
                        FileSizeFormatter.formatFileSize(total),
                        FileSizeFormatter.formatFileSize(fileSize),
                        progress,
                        FileSizeFormatter.formatSpeed(calculateSpeed(incrementTotal)));
            }, 0, 1, TimeUnit.SECONDS);

            // 等待下载完成
            downloadExecutor.shutdown();
            downloadExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            scheduler.shutdown();
            logger.info("Download completed: {} → {}", url, savePath);
            FileUtil.del(progressDir);
        } catch (Exception e) {
            logger.error("Download failed||url={}", url, e);
        }
    }

    private String extractFileNameFromUrl(String url) {
        try {
            return Paths.get(new java.net.URL(url).getPath()).getFileName().toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
    }

    private String getFileName(String url) {
        return extractFileNameFromUrl(url);
    }

    private void checkAndCleanThreadCount(File progressDir, File outputFile) {
        File threadCountFile = new File(progressDir, "thread_count");
        try {
            if (threadCountFile.exists()) {
                int oldCount = Integer.parseInt(FileUtils.readFileToString(threadCountFile, StandardCharsets.UTF_8.name()));
                if (oldCount != threadCount) {
                    logger.warn("Thread count changed from {} to {}, cleaning old data", oldCount, threadCount);
                    for (File file : progressDir.listFiles()) {
                        file.delete();
                    }
                    outputFile.delete();
                }
            }
            FileUtils.writeStringToFile(threadCountFile, String.valueOf(threadCount));
        } catch (IOException e) {
            logger.error("Failed to check thread count", e);
        }
    }

    private double calculateSpeed(long total) {
        long elapsedTime = (System.currentTimeMillis() - startTime) / 1000;
        if (elapsedTime <= 0) {
            return 0;
        }
        return (double) total / elapsedTime;
    }
}