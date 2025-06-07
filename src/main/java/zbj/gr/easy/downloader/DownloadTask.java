package zbj.gr.easy.downloader;


import cn.hutool.core.thread.ThreadUtil;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Random;

public class DownloadTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(DownloadTask.class);
    private static final int MAX_RETRIES = 30; // 最大重试次数
    private static final int BLOCK_SIZE = 4 * 1024 * 1024; // 每个块 4MB
    private static final int BUFFER_SIZE = BLOCK_SIZE; // 缓冲区大小

    private final CloseableHttpClient httpClient;
    private final String url;
    private final RandomAccessFile randomAccessFile;
    private final long startByte;
    private final long endByte;
    private final File progressFile;
    private long downloadedBytes;
    private long incrementDownloadBytes;
    private int timeout = 5000;

    public DownloadTask(CloseableHttpClient httpClient, String url, RandomAccessFile randomAccessFile, long startByte, long endByte, File progressFile) {
        this.httpClient = httpClient;
        this.url = url;
        this.randomAccessFile = randomAccessFile;
        this.startByte = startByte;
        this.endByte = endByte;
        this.progressFile = progressFile;
        this.downloadedBytes = readDownloadedBytesFromProgressFile();
    }

    @Override
    public void run() {
        long currentStart = startByte + downloadedBytes;
        while (currentStart <= endByte) {
            long currentEnd = Math.min(currentStart + BLOCK_SIZE - 1, endByte);
            downloadBlock(currentStart, currentEnd);
            currentStart += BLOCK_SIZE;
        }
        // 完成后清除进度文件
        progressFile.delete();
    }

    private void downloadBlock(long blockStart, long blockEnd) {
        int retryCount = 0;
        boolean success = false;
        Random random = new Random();
        while (!success && retryCount < MAX_RETRIES) {
            try {
                RequestConfig requestConfig = RequestConfig.custom()
                        .setConnectTimeout(timeout)    // 连接超时（毫秒）
                        .setSocketTimeout(timeout)    // Socket 超时（毫秒）
//                        .setConnectionRequestTimeout(timeout) // 从连接池获取连接的超时时间（可选）
                        .build();
                HttpGet request = new HttpGet(url);
                request.setConfig(requestConfig);
                request.setHeader("Range", "bytes=" + blockStart + "-" + blockEnd);
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    if (statusCode == 206) {
                        HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            try (InputStream inputStream = entity.getContent()) {
                                byte[] buffer = new byte[BUFFER_SIZE];
                                int bytesRead;
                                randomAccessFile.seek(blockStart);
                                while ((bytesRead = inputStream.read(buffer)) != -1) {
                                    randomAccessFile.write(buffer, 0, bytesRead);
                                    downloadedBytes += bytesRead;
                                    updateProgressFile(downloadedBytes);
                                    incrementDownloadBytes += bytesRead;
                                }
                            }
                        }
                    } else {
                        logger.warn("Unexpected status code: {}", statusCode);
                        throw new IOException("unexpected status code " + statusCode + ",line=" + response.getStatusLine().getReasonPhrase());
                    }
                }
                success = true;
            } catch (IOException e) {
                retryCount++;
                ThreadUtil.sleep(random.nextInt(3000) + 300);
                logger.warn("Attempt {} failed for block [{}-{}]. Retrying...", retryCount, blockStart, blockEnd);
                if (retryCount >= MAX_RETRIES) {
                    logger.error("Max retries reached. Failed to download block.", e);
                }
            }
        }
    }

    private long readDownloadedBytesFromProgressFile() {
        if (!progressFile.exists()) {
            return 0;
        }
        try (FileInputStream fis = new FileInputStream(progressFile)) {
            byte[] buffer = new byte[8];
            if (fis.read(buffer) == 8) {
                return ByteBuffer.wrap(buffer).getLong();
            }
        } catch (IOException e) {
            logger.warn("Failed to read progress file", e);
        }
        return 0;
    }

    private void updateProgressFile(long bytes) {
        try (FileOutputStream fos = new FileOutputStream(progressFile)) {
            fos.write(ByteBuffer.allocate(8).putLong(bytes).array());
        } catch (IOException e) {
            logger.warn("Failed to write progress file", e);
        }
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public long getIncrementDownloadBytes() {
        return incrementDownloadBytes;
    }

    public static CloseableHttpClient createHttpClientWithProxy(String proxyHost, int proxyPort) {
        HttpHost proxy = new HttpHost(proxyHost, proxyPort);
        DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
        return HttpClients.custom()
                .setRoutePlanner(routePlanner)
                .build();
    }
}