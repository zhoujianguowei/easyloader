package zbj.gr.easy.downloader.youtube;


import com.github.kiulian.downloader.Config;
import com.github.kiulian.downloader.YoutubeDownloader;
import com.github.kiulian.downloader.downloader.YoutubeProgressCallback;
import com.github.kiulian.downloader.downloader.client.ClientType;
import com.github.kiulian.downloader.downloader.client.Clients;
import com.github.kiulian.downloader.downloader.request.RequestPlaylistInfo;
import com.github.kiulian.downloader.downloader.request.RequestVideoFileDownload;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.model.Extension;
import com.github.kiulian.downloader.model.playlist.PlaylistInfo;
import com.github.kiulian.downloader.model.playlist.PlaylistVideoDetails;
import com.github.kiulian.downloader.model.videos.VideoInfo;
import com.github.kiulian.downloader.model.videos.formats.AudioFormat;
import com.github.kiulian.downloader.model.videos.formats.Format;
import com.github.kiulian.downloader.model.videos.formats.VideoFormat;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.github.kiulian.downloader.downloader.client.ClientType.*;

@Slf4j
public class YoutubeDownloaderService {

    private final YoutubeDownloader downloader;
    private final ExecutorService executorService;

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("(?:youtube(?:-nocookie)?\\.com/(?:[^/\\n\\s]+/\\S+/|(?:v|e(?:mbed)?)/|\\S*?[?&]v=)|youtu\\.be/)([a-zA-Z0-9_-]{11})");
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("[?&]list=([^&\\n\\s]+)");

    public static String extractVideoId(String youtubeUrl) {
        if (youtubeUrl == null) return null;
        Matcher matcher = VIDEO_ID_PATTERN.matcher(youtubeUrl);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String extractPlaylistId(String youtubeUrl) {
        if (youtubeUrl == null) return null;
        Matcher matcher = PLAYLIST_ID_PATTERN.matcher(youtubeUrl);
        return matcher.find() ? matcher.group(1) : null;
    }

    public YoutubeDownloaderService() {
        Config config = new Config.Builder().build();
        this.downloader = new YoutubeDownloader(config);
        Clients.setHighestPriorityClientType(MWEB);
        this.executorService = Executors.newFixedThreadPool(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))
        );
        log.info("YoutubeDownloaderService initialized.");
    }

    // ... (download, downloadPlaylistInternal 方法保持之前基于3.3.1版本README的实现) ...
    public void download(String videoOrPlaylistUrl, String outputDirectory, VideoResolution resolution, boolean downloadPlaylist) {
        String videoId = extractVideoId(videoOrPlaylistUrl);
        String playlistId = extractPlaylistId(videoOrPlaylistUrl);

        if (downloadPlaylist && playlistId != null) {
            log.info("Playlist download explicitly requested for playlist ID: {}. URL: {}", playlistId, videoOrPlaylistUrl);
            downloadPlaylistInternal(playlistId, outputDirectory, resolution);
        } else if (videoId != null) {
            log.info("Single video download requested for video ID: {}. URL: {}", videoId, videoOrPlaylistUrl);
            submitDownloadTask(videoId, null, outputDirectory, resolution);
        } else if (playlistId != null) {
            log.warn("URL is a playlist, but 'downloadPlaylist' is false. Attempting to download the first video from playlist ID: {}", playlistId);
            try {
                RequestPlaylistInfo request = new RequestPlaylistInfo(playlistId);
                Response<PlaylistInfo> response = downloader.getPlaylistInfo(request);
                if (response.ok()) {
                    PlaylistInfo playlist = response.data();
                    if (playlist.videos() != null && !playlist.videos().isEmpty()) {
                        PlaylistVideoDetails firstVideo = playlist.videos().get(0);
                        log.info("Downloading first video from playlist: '{}' (ID: {})", firstVideo.title(), firstVideo.videoId());
                        submitDownloadTask(firstVideo.videoId(), firstVideo.title(), outputDirectory, resolution);
                    } else {
                        log.error("Playlist {} is empty or videos could not be retrieved.", playlistId);
                    }
                } else {
                    log.error("Failed to retrieve playlist info for {}: {}", playlistId, response.error() != null ? response.error().getMessage() : "Unknown error");
                }
            } catch (Exception e) {
                log.error("Error fetching first video from playlist {}: {}", playlistId, e.getMessage(), e);
            }
        } else {
            log.error("Invalid YouTube URL: {}. Could not extract video or playlist ID.", videoOrPlaylistUrl);
        }
    }

    private void downloadPlaylistInternal(String playlistId, String outputDirectory, VideoResolution resolution) {
        try {
            RequestPlaylistInfo request = new RequestPlaylistInfo(playlistId);
            Response<PlaylistInfo> response = downloader.getPlaylistInfo(request);

            if (response.ok()) {
                PlaylistInfo playlist = response.data();
                log.info("Starting download for playlist: '{}' ({} videos)", playlist.details().title(), playlist.videos().size());
                for (PlaylistVideoDetails videoDetails : playlist.videos()) {
                    submitDownloadTask(videoDetails.videoId(), videoDetails.title(), outputDirectory, resolution);
                }
            } else {
                log.error("Failed to retrieve playlist info for ID {}: {}", playlistId, response.error() != null ? response.error().getMessage() : "Unknown error");
            }
        } catch (Exception e) {
            log.error("Error downloading playlist {}: {}", playlistId, e.getMessage(), e);
        }
    }

    // 改进后的 selectFormat 方法
    private Format selectFormat(VideoInfo videoInfo, VideoResolution resolution) {
        // 1. 处理纯音频请求
        if (resolution == VideoResolution.AUDIO_ONLY_BEST_M4A) {
            return videoInfo.audioFormats().stream()
                    .filter(af -> Extension.M4A.equals(af.extension()))
                    .max(Comparator.comparingInt(Format::bitrate))
                    .orElseGet(() -> { // 如果没有M4A，返回任意最佳音频
                        log.warn("No M4A audio found, falling back to best available audio format.");
                        return videoInfo.bestAudioFormat();
                    });
        }
        if (resolution == VideoResolution.AUDIO_ONLY_MP3) {
            log.warn("MP3 format selected, but library downloads M4A (or best available audio). External conversion needed.");
            return videoInfo.audioFormats().stream() // 同样尝试找M4A
                    .filter(af -> Extension.M4A.equals(af.extension()))
                    .max(Comparator.comparingInt(Format::bitrate))
                    .orElseGet(() -> videoInfo.bestAudioFormat());
        }

        // 2. 对于视频请求，优先使用预合并的音视频流
        List<VideoFormat> selectableVideoFormats = videoInfo.videoWithAudioFormats().stream().collect(Collectors.toList());

        if (selectableVideoFormats.isEmpty()) {
            log.warn("No pre-merged video with audio formats found for video: {}. Cannot select specific resolution. " +
                            "Consider if downloading separate video and audio streams and merging them is an option (not supported by this service directly).",
                    videoInfo.details().title());
            // 作为最后的手段，如果用户请求了 HIGHEST，可以尝试返回库认为的 bestVideoFormat（可能是无声的）
            if (resolution == VideoResolution.HIGHEST) {
                log.warn("Falling back to best video-only format for HIGHEST resolution request.");
                return videoInfo.bestVideoFormat();
            }
            return null; // 对于其他特定分辨率，如果没有预合并流，则无法满足
        }

        // 筛选出 MP4 格式的 (如果池中有的话，优先使用)
        List<VideoFormat> mp4SelectableVideoFormats = selectableVideoFormats.stream()
                .filter(f -> f.mimeType() != null && f.mimeType().contains("mp4"))
                .collect(Collectors.toList());

        List<VideoFormat> formatsToSearch = !mp4SelectableVideoFormats.isEmpty() ? mp4SelectableVideoFormats : selectableVideoFormats;
        if (formatsToSearch == mp4SelectableVideoFormats) {
            log.debug("Prioritizing MP4 formats from pre-merged streams ({} found).", formatsToSearch.size());
        } else {
            log.debug("No MP4 pre-merged streams found, using all pre-merged streams ({} found).", formatsToSearch.size());
        }


        // 3. 根据 VideoResolution 选择
        switch (resolution) {
            case HIGHEST:
                // VideoInfo.bestVideoWithAudioFormat() 返回的是 VideoFormat，
                // 它会基于 VideoQuality 比较。这通常是我们想要的。
                Format bestOverall = videoInfo.bestVideoWithAudioFormat();
                if (bestOverall != null) {
                    return bestOverall;
                }
                // 如果上面的方法返回 null (例如所有预合并格式的 videoQuality() 都为 null 或比较出问题)
                // 则手动从 formatsToSearch (优先mp4) 中选择
                log.warn("videoInfo.bestVideoWithAudioFormat() returned null. Manually selecting from available pre-merged formats.");
                return formatsToSearch.stream()
                        .max(Comparator.comparingInt(VideoFormat::height).thenComparingInt(Format::bitrate))
                        .orElse(null);

            case RES_2160P:
                return findFormatByHeight(formatsToSearch, 2160);
            case RES_1440P:
                return findFormatByHeight(formatsToSearch, 1440);
            case RES_1080P:
                return findFormatByHeight(formatsToSearch, 1080);
            case RES_720P:
                return findFormatByHeight(formatsToSearch, 720);
            case RES_480P:
                return findFormatByHeight(formatsToSearch, 480);
            case RES_360P:
                return findFormatByHeight(formatsToSearch, 360);
            default:
                log.warn("Unsupported VideoResolution enum: {}. Falling back to highest available from the selected pool.", resolution);
                return formatsToSearch.stream()
                        .max(Comparator.comparingInt(VideoFormat::height).thenComparingInt(Format::bitrate))
                        .orElse(null);
        }
    }


    // findFormatByHeight 辅助方法保持不变
    private Format findFormatByHeight(List<VideoFormat> formats, int targetHeight) {
        // 优先精确匹配高度，然后选择码率最高的
        Optional<VideoFormat> exactMatch = formats.stream()
                .filter(vf -> vf.height() != null && vf.height() == targetHeight)
                .max(Comparator.comparingInt(Format::bitrate));
        if (exactMatch.isPresent()) {
            log.debug("Found exact match for height {}: Itag {}", targetHeight, exactMatch.get().itag().id());
            return exactMatch.get();
        }

        // 如果没有精确匹配，尝试找到最接近且不超过目标高度的，码率最高的
        log.debug("No exact match for height {}. Searching for closest (<=) height within the pool of {} formats.", targetHeight, formats.size());
        return formats.stream()
                .filter(vf -> vf.height() != null && vf.height() <= targetHeight)
                .max(Comparator.comparingInt(VideoFormat::height) // 首先尽量接近目标高度（越大越好，但不超过）
                        .thenComparingInt(Format::bitrate))   // 在同样高度下，码率越高越好
                .orElseGet(() -> {
                    log.warn("No format found at or below target height {}. Returning null.", targetHeight);
                    return null;
                });
    }

    public static List<ClientType> getAllClientTypes() {
        return Arrays.asList(
                WEB, MWEB, ANDROID, IOS, TVHTML5, TVLITE, TVANDROID, XBOXONEGUIDE,
                ANDROID_CREATOR, IOS_CREATOR, TVAPPLE, ANDROID_KIDS, IOS_KIDS,
                ANDROID_MUSIC, ANDROID_TV, IOS_MUSIC, MWEB_TIER_2, ANDROID_VR,
                ANDROID_UNPLUGGED, ANDROID_TESTSUITE, WEB_MUSIC_ANALYTICS, IOS_UNPLUGGED,
                ANDROID_LITE, IOS_EMBEDDED_PLAYER, WEB_UNPLUGGED, WEB_EXPERIMENTS,
                TVHTML5_CAST, ANDROID_EMBEDDED_PLAYER, WEB_EMBEDDED_PLAYER, TVHTML5_AUDIO,
                TV_UNPLUGGED_CAST, TVHTML5_KIDS, WEB_HEROES, WEB_MUSIC, WEB_CREATOR,
                TV_UNPLUGGED_ANDROID, IOS_LIVE_CREATION_EXTENSION, TVHTML5_UNPLUGGED,
                IOS_MESSAGES_EXTENSION, WEB_REMIX, IOS_UPTIME, WEB_UNPLUGGED_ONBOARDING,
                WEB_UNPLUGGED_OPS, WEB_UNPLUGGED_PUBLIC, TVHTML5_VR, ANDROID_TV_KIDS,
                TVHTML5_SIMPLY, WEB_KIDS, MUSIC_INTEGRATIONS, TVHTML5_YONGLE,
                GOOGLE_ASSISTANT, TVHTML5_SIMPLY_EMBEDDED_PLAYER, WEB_INTERNAL_ANALYTICS,
                WEB_PARENT_TOOLS, GOOGLE_MEDIA_ACTIONS, WEB_PHONE_VERIFICATION,
                IOS_PRODUCER, TVHTML5_FOR_KIDS, GOOGLE_LIST_RECS, MEDIA_CONNECT_FRONTEND
        );
    }

    // submitDownloadTask 方法使用新的 selectFormat
    private void submitDownloadTask(String videoId, String titleHint, String outputDirectory, VideoResolution resolutionEnum) {
        executorService.submit(() -> {
            String effectiveVideoTitle = "Video ID " + videoId;
            try {
                Response<VideoInfo> responseInfo = null;
                boolean success = false;
                for (ClientType clientType : getAllClientTypes()) {
                    RequestVideoInfo requestInfo = new RequestVideoInfo(videoId).clientType(clientType);
                    responseInfo = downloader.getVideoInfo(requestInfo);
                    if (!responseInfo.ok()) {
                        log.error("Failed to get video info for ID={}||clientType={}||exception={}",
                                videoId, clientType.getName(), responseInfo.error() != null ? responseInfo.error().getMessage() : "Unknown error");
                        continue;
                    }
                    log.info("success extract video info||clientType={}", clientType.getName());
                    success = true;
                }
                if (!success) {
                    return;
                }
                VideoInfo videoInfo = responseInfo.data();
                String videoTitleFromInfo = videoInfo.details().title();
                effectiveVideoTitle = (titleHint != null && !titleHint.isEmpty()) ? titleHint : videoTitleFromInfo;
                String cleanVideoTitle = effectiveVideoTitle.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (cleanVideoTitle.isEmpty()) cleanVideoTitle = "youtube_video_" + videoId;
                effectiveVideoTitle = cleanVideoTitle;

                log.info("Preparing download for: '{}' (ID: {})", cleanVideoTitle, videoId);
                log.info("Requested resolution: {}", resolutionEnum.getDescription());

                Format selectedFormat = selectFormat(videoInfo, resolutionEnum); // 使用新的选择逻辑

                if (selectedFormat == null) {
                    log.error("Could not find a suitable format for video '{}' with resolution {}. Attempting fallback.", cleanVideoTitle, resolutionEnum.getDescription());
                    // 尝试下载一个可用的最高质量MP4带音频，或任意最高质量（如果selectFormat返回null）
                    selectedFormat = videoInfo.bestVideoWithAudioFormat(); // 库提供的最佳带音频格式
                    if (selectedFormat == null) {
                        log.error("No available format (even fallback) found for video '{}'. Download aborted.", cleanVideoTitle);
                        return;
                    }
                    log.warn("Falling back to library's best video with audio format: Itag {}, Ext: {}, Quality: {}",
                            selectedFormat.itag(), selectedFormat.extension().value(),
                            (selectedFormat instanceof VideoFormat) ? ((VideoFormat) selectedFormat).qualityLabel() : "N/A (Audio)");
                } else {
                    log.info("Selected format: Itag {}, Ext: {}, Quality: {}, MimeType: {}",
                            selectedFormat.itag(), selectedFormat.extension().value(),
                            (selectedFormat instanceof VideoFormat) ? ((VideoFormat) selectedFormat).qualityLabel() : "N/A (Audio)",
                            selectedFormat.mimeType());
                }

                String finalCleanVideoTitle = cleanVideoTitle;
                RequestVideoFileDownload requestDownload = new RequestVideoFileDownload(selectedFormat)
                        .saveTo(new File(outputDirectory))
                        .renameTo(cleanVideoTitle)
                        .overwriteIfExists(false)
                        .callback(new YoutubeProgressCallback<File>() {
                            @Override
                            public void onDownloading(int progress) {
                                log.info("Video: '{}' - Progress: {}%", finalCleanVideoTitle, progress);
                            }

                            @Override
                            public void onFinished(File file) {
                                log.info("Successfully downloaded: '{}' to '{}'", finalCleanVideoTitle, file.getAbsolutePath());
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                log.error("Error during download of '{}': {}", finalCleanVideoTitle, throwable.getMessage(), throwable);
                            }
                        });

                log.debug("Submitting download request for '{}'", cleanVideoTitle);
                Response<File> responseDownload = downloader.downloadVideoFile(requestDownload);

                if (!responseDownload.ok()) {
                    log.error("Download failed for '{}'. Response status: {}. Error: {}",
                            cleanVideoTitle, responseDownload.status(),
                            responseDownload.error() != null ? responseDownload.error().getMessage() : "N/A");
                }

            } catch (Exception e) {
                log.error("Exception during download process for {}: {}", effectiveVideoTitle, e.getMessage(), e);
            }
        });
    }

    // ... (listAvailableFormats, shutdown 方法保持之前基于3.3.1版本README的实现) ...
    public void listAvailableFormats(String videoUrl) {
        String videoId = extractVideoId(videoUrl);
        if (videoId == null) {
            log.error("Invalid video URL for listing formats: {}", videoUrl);
            return;
        }
        try {
            RequestVideoInfo request = new RequestVideoInfo(videoId);
            Response<VideoInfo> response = downloader.getVideoInfo(request);
            if (response.ok()) {
                VideoInfo video = response.data();
                log.info("Available formats for '{}':", video.details().title());
                if (video.formats().isEmpty()) {
                    log.warn("No formats found for this video.");
                    return;
                }
                video.formats().forEach(format -> {
                    String type = format.type(); // 直接用 type()
                    if (format instanceof VideoFormat) {
                        VideoFormat vf = (VideoFormat) format;
                        log.info("  Itag: {}, Ext: {}, Type: {}, Quality: {}, Resolution: {}x{}, FPS: {}, Bitrate: {}kbps",
                                vf.itag(), vf.extension().value(), type,
                                vf.qualityLabel(), vf.width(), vf.height(), vf.fps(), vf.bitrate() != null ? vf.bitrate() / 1000 : "N/A");
                    } else if (format instanceof AudioFormat) {
                        AudioFormat af = (AudioFormat) format;
                        log.info("  Itag: {}, Ext: {}, Type: {}, Quality: {}, SampleRate: {}Hz, Bitrate: {}kbps",
                                af.itag(), af.extension().value(), type,
                                af.audioQuality(), af.audioSampleRate(), af.bitrate() != null ? af.bitrate() / 1000 : "N/A");
                    } else {
                        log.info("  Itag: {}, Ext: {}, Type: {}, MimeType: {}",
                                format.itag(), format.extension().value(), type, format.mimeType());
                    }
                });
            } else {
                log.error("Failed to get video info for listing formats (ID: {}): {}", videoId, response.error() != null ? response.error().getMessage() : "Unknown error");
            }
        } catch (Exception e) {
            log.error("Error fetching formats for video ID {}: {}", videoId, e.getMessage(), e);
        }
    }

    public void shutdown() {
        log.info("Shutting down YoutubeDownloaderService executor...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("Executor did not terminate.");
                }
            }
        } catch (InterruptedException ie) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("YoutubeDownloaderService executor shut down.");
    }
}