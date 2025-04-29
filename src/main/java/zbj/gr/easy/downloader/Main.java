package zbj.gr.easy.downloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Proxy;

public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String url = "https://huggingface.co/huihui-ai/Qwen2.5-Coder-32B-Instruct-abliterated/resolve/main/model-00001-of-00014.safetensors";
        String savePath = "model-00001-of-00014.safetensors";
        boolean resume = true; // 是否支持断点续传

        // 配置代理（可选）
        Downloader downloader = new Downloader.Builder()
                .url(url)
                .savePath(savePath)
                .resume(resume)
                .proxy("127.0.0.1", 10808, Proxy.Type.HTTP) // 配置 HTTP 代理
                .build();

        downloader.start();
    }
}
