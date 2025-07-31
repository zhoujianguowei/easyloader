package zbj.gr.easy.downloader;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static ExecutorService executorService = Executors.newFixedThreadPool(6);

    public static void main(String[] args) {
        int size = 2;
        CountDownLatch countDownLatch = new CountDownLatch(size);
        List<String> urlList = Lists.newArrayList();
        for (int i = 1; i <= size; i++) {
            String index = i < 10 ? String.format("0%d", i) : String.valueOf(i);
            String url = urlList.get(i - 1);
            String proxyHost = "127.0.0.1"; // 代理主机地址
            int proxyPort = 7897; // 代理端口
            FileDownloader downloader = FileDownloader.builder().url(url).build();
            executorService.submit(() -> {
                LOGGER.info("begin to download {}", url);
                downloader.download();
                countDownLatch.countDown();
            });
        }
        try {
            countDownLatch.await();
            executorService.shutdown();
        } catch (InterruptedException e) {
            LOGGER.error("interrupted ", e);
        }
//        MultiThreadedDownloader.builder().url("https://huggingface.co/Qwen/Qwen3-32B/resolve/main/tokenizer.json")
//                .proxy("127.0.0.1", 10808)
//                .build().download();
    }
}
