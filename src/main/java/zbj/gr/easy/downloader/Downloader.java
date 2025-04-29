package zbj.gr.easy.downloader;


import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.*;
import okio.BufferedSink;
import okio.Okio;

public class Downloader {
    private static final int BUFFER_SIZE = 8192; // 缓冲区大小（8KB）
    private static final DecimalFormat DF = new DecimalFormat("0.00");

    private final String url;
    private final String savePath;
    private final boolean resume;
    private final long totalSize;
    private long downloadedSize;
    private long startTime;
    private volatile boolean isRunning = true;
    private final OkHttpClient client;

    // 使用 Builder 模式配置参数
    public static class Builder {
        private String url;
        private String savePath;
        private boolean resume = false;
        private String proxyHost;
        private int proxyPort = 0;
        private Proxy.Type proxyType = Proxy.Type.HTTP;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder savePath(String path) {
            this.savePath = path;
            return this;
        }

        public Builder resume(boolean resume) {
            this.resume = resume;
            return this;
        }

        public Builder proxy(String host, int port, Proxy.Type type) {
            this.proxyHost = host;
            this.proxyPort = port;
            this.proxyType = type;
            return this;
        }

        public Downloader build() {
            if (url == null || savePath == null) {
                throw new IllegalArgumentException("URL 和保存路径不能为空");
            }
            return new Downloader(this);
        }
    }

    private Downloader(Builder builder) {
        this.url = builder.url;
        this.savePath = builder.savePath;
        this.resume = builder.resume;

        // 创建 OkHttpClient 并配置代理
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);

        if (builder.proxyHost != null && builder.proxyPort > 0) {
            Proxy proxy = new Proxy(builder.proxyType,
                    new InetSocketAddress(builder.proxyHost, builder.proxyPort));
            clientBuilder.proxy(proxy);
        }

        this.client = clientBuilder.build();

        // 获取文件总大小
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP 请求失败: " + response.code());
            }
            this.totalSize = response.body().contentLength();
        } catch (IOException e) {
            throw new RuntimeException("获取文件信息失败", e);
        }
    }

    public void start() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(this::download);
        executor.shutdown();
    }

    private void download() {
        downloadedSize = 0;
        startTime = System.currentTimeMillis();

        // 检查是否需要续传
        long startByte = 0;
        File file = new File(savePath);
        if (resume && file.exists()) {
            startByte = file.length();
            if (startByte >= totalSize) {
                System.out.println("文件已完整，无需下载。");
                return;
            }
        }

        // 发送带 Range 头的请求
        Request request = new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + startByte + "-")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP 请求失败: " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("响应体为空");
            }

            // 创建输出流（追加模式）
            File saveFile = new File(savePath);
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(saveFile, "rw")) {
                randomAccessFile.seek(startByte); // 定位到续传位置
                BufferedSink sink = Okio.buffer(Okio.sink(saveFile));

                byte[] buffer = new byte[BUFFER_SIZE];
                long bytesRead;
                while ((bytesRead = body.byteStream().read(buffer)) != -1 && isRunning) {
                    downloadedSize += bytesRead;
                    sink.write(buffer, 0, (int) bytesRead);
                    updateProgress(downloadedSize);
                }
                sink.close();
            }
        } catch (IOException e) {
            System.err.println("下载失败: " + e.getMessage());
        } finally {
            if (isRunning) {
                System.out.println("下载完成！");
            }
        }
    }

    private void updateProgress(long current) {
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = (current / 1024.0) / (elapsed / 1000.0); // KB/s

        System.out.printf("\r下载进度: %s%% | 已下载: %s KB | 速度: %.2f KB/s",
                DF.format((current * 100.0) / totalSize),
                DF.format(current / 1024.0),
                speed);
    }

    public void cancel() {
        isRunning = false;
    }
}