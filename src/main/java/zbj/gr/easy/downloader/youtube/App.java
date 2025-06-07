package zbj.gr.easy.downloader.youtube;


import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Slf4j
public class App {
    public static void main(String[] args) {
        // ytDlpPath 参数不再需要，因为这个库不直接使用外部 yt-dlp
        // String ytDlpPath = "yt-dlp/yt-dlp";

        YoutubeDownloaderService downloaderService = new YoutubeDownloaderService(); // 无需 ytDlpPath

        String outputDir = "downloads";
        try {
            Files.createDirectories(Paths.get(outputDir));
        } catch (IOException e) {
            log.error("Could not create output directory: {}", outputDir, e);
            return;
        }

        // --- 示例 ---

        // 示例1: 下载单个视频，最高分辨率
        String singleVideoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        downloaderService.listAvailableFormats(singleVideoUrl);
        downloaderService.download(singleVideoUrl, outputDir, VideoResolution.HIGHEST, false);

        // 示例2: 下载单个视频，指定 720p (selectFormat 中需要有对应逻辑)
        String anotherVideoUrl = "https://www.youtube.com/watch?v=kJQP7kiw5Fk";
        // downloaderService.download(anotherVideoUrl, outputDir, VideoResolution.RES_720P, false);

        // 示例3: URL 是视频集的一部分, 只下载当前视频
        String playlistVideoUrl = "https://www.youtube.com/watch?v=RARSxwT8sEg&list=PLMC9KNkIncKtPzgY-5rmFqhdMVgZkZpoz&index=1";
        // downloaderService.download(playlistVideoUrl, outputDir, VideoResolution.RES_480P, false);

        // 示例4: URL 是视频集的一部分, 下载整个视频集
        String playlistUrlForFullDownload = "https://www.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmFqhdMVgZkZpoz";
        downloaderService.download(playlistUrlForFullDownload, outputDir, VideoResolution.RES_360P, true);


        log.info("All download tasks submitted. Application will exit once tasks complete or if an error occurs during submission.");
        // Runtime.getRuntime().addShutdownHook(new Thread(downloaderService::shutdown)); // 在JVM关闭时尝试关闭线程池
    }
}