package zbj.gr.easy.downloader.youtube;


import lombok.Getter;

@Getter
public enum VideoResolution {
    HIGHEST("最高分辨率", -1), // -1 表示特殊处理
    RES_2160P("2160p (4K)", 2160),
    RES_1440P("1440p (2K)", 1440),
    RES_1080P("1080p (HD)", 1080),
    RES_720P("720p (HD)", 720),
    RES_480P("480p (SD)", 480),
    RES_360P("360p", 360),
    AUDIO_ONLY_BEST_M4A("仅音频 (最佳 M4A)", 0), // 0 表示音频
    AUDIO_ONLY_MP3("仅音频 (MP3, 需转码)", 0);

    private final String description;
    private final int targetHeight; // 0 for audio, -1 for highest video

    VideoResolution(String description, int targetHeight) {
        this.description = description;
        this.targetHeight = targetHeight;
    }

    public boolean isAudioOnly() {
        return this.targetHeight == 0;
    }

    @Override
    public String toString() {
        return description;
    }
}