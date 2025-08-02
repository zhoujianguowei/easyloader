package zbj.gr.easy.hugging;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Pair;
import cn.hutool.core.thread.ThreadUtil;
import com.google.common.collect.Lists;
import com.grw.xiaobai.util.CommandExecutor;
import com.grw.xiaobai.util.CompletableFutureUtil;
import com.grw.xiaobai.util.StorageUnit;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zbj.gr.easy.downloader.FileSizeFormatter;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RetryHuggingDownload {
    private static final String HUGGING_COMMAND = "huggingface-cli";
    private static final Logger LOGGER = LoggerFactory.getLogger(RetryHuggingDownload.class);
    private static final long MIN_RETRY_DOWNLOAD_THRESHOLD_BYTE_SPEED = StorageUnit.MB.toBytes(2);
    private static final long MIN_LAUNCH_DOWNLOAD_THRESHOLD_BYTE_SPEED = 100;

    static {
        CommandExecutor.CommandResult result = CommandExecutor.executeCommandWithoutRefreshBash(
                Lists.newArrayList("which", HUGGING_COMMAND), TimeUnit.SECONDS.toMillis(3));
        Assert.isTrue(result.isSuccess() && StringUtils.isNotBlank(result.getStdOutput()), "huggingface-cli command non exists");
    }


    public static List<Pair<String, long[]>> getNetSpeed() {
        List<String> cmdList = Lists.newArrayList("ifstat", "-n", "3", "1");
        CommandExecutor.CommandResult result = CommandExecutor.executeCommandWithoutRefreshBash(cmdList, TimeUnit.SECONDS.toMillis(20));
        Assert.isTrue(result.isSuccess() && StringUtils.isNotBlank(result.getStdOutput()));
        return parseIfstatOutput(result.getStdOutput());

    }

    // 使用StorageUnit进行单位转换
    private static final long KB_TO_BYTES = StorageUnit.KB.toBytes(1);

    /**
     * 解析ifstat命令输出的字符串
     *
     * @param output ifstat命令的输出字符串
     * @return List<Pair < String, long [ ]>>，其中：
     * - Pair的key: 网卡名
     * - Pair的value: long数组 [下行流量(Byte/s), 上行流量(Byte/s)]
     */
    private static List<Pair<String, long[]>> parseIfstatOutput(String output) {
        String[] lines = output.split("\\r?\\n");
        if (lines.length < 3) {
            throw new IllegalArgumentException("Invalid ifstat output: too few lines");
        }

        // 解析网卡名（第一行）
        List<String> interfaces = parseInterfaceNames(lines[0]);

        // 解析流量数据（第三行）
        String[] values = parseTrafficValues(lines[2]);
        if (values.length != interfaces.size() * 2) {
            throw new IllegalArgumentException("Expected 4 traffic values, found: " + values.length);
        }

        // 构建结果列表
        List<Pair<String, long[]>> result = new ArrayList<>();
        for (int i = 0; i < interfaces.size(); i++) {
            long[] traffic = new long[2];
            traffic[0] = convertToBytes(Double.parseDouble(values[2 * i]));
            traffic[1] = convertToBytes(Double.parseDouble(values[2 * i + 1]));
            Pair<String, long[]> pair = new Pair<>(interfaces.get(i), traffic);
            result.add(pair);
        }
        return result;
    }

    private static long convertToBytes(double kbPerSecond) {
        // 使用StorageUnit进行单位转换：KB/s → Byte/s
        // 1 KB = 1024 bytes，所以 1 KB/s = 1024 Byte/s
        return Math.round(kbPerSecond * KB_TO_BYTES);
    }

    private static List<String> parseInterfaceNames(String line) {
        Pattern pattern = Pattern.compile("\\S+");
        Matcher matcher = pattern.matcher(line);
        List<String> interfaces = new ArrayList<>();
        while (matcher.find()) {
            interfaces.add(matcher.group());
        }
        return interfaces;
    }

    private static String[] parseTrafficValues(String line) {
        Pattern pattern = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+");
        Matcher matcher = pattern.matcher(line);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values.toArray(new String[0]);
    }

    public void downloadHuggingWholeRepo(String repoPath, File saveDir) {
        if (!saveDir.exists() || !saveDir.isDirectory()) {
            Assert.isTrue(saveDir.mkdirs(), String.format("failed to create %s dir", saveDir.getAbsolutePath()));
        }
        List<String> cmdList = Lists.newArrayList(HUGGING_COMMAND, "download", repoPath,
                "--local-dir", saveDir.getAbsolutePath());
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(4);
        AtomicBoolean shouldStop = new AtomicBoolean(false);
        Consumer<String> stringConsumer = str -> {
            LOGGER.info("repoPath {} download info {}", repoPath, saveDir);
        };
        killDownloadProcess(repoPath);
        long waitProcessStartTimeoutMillis = TimeUnit.SECONDS.toMillis(20);
        Runnable detectRetryRunnable = () -> {
            try {
                long maxInSpeed = getNetSpeed().stream().max(Comparator.comparingLong(p -> p.getValue()[0]))
                        .map(Pair::getValue).map(arr -> arr[0]).orElse(0L);
                if (maxInSpeed < MIN_RETRY_DOWNLOAD_THRESHOLD_BYTE_SPEED) {
                    LOGGER.warn("current download speed a little low,kill and retry||currentSpeed={} byte/s", maxInSpeed);
                    killDownloadProcess(repoPath);
                    ThreadUtil.sleep(waitProcessStartTimeoutMillis);
                }
                LOGGER.info("download speed {}", FileSizeFormatter.formatFileSize(maxInSpeed));
            } catch (Exception e) {
                LOGGER.error("execute detect task exception repoPath={}", repoPath, e);
                if (e instanceof IllegalArgumentException) {
                    LOGGER.warn("stop task");
                    shouldStop.set(true);
                    killDownloadProcess(repoPath);
                }
            }
        };
        scheduledExecutorService.scheduleWithFixedDelay(detectRetryRunnable, 20, 10, TimeUnit.SECONDS);
        try {
            while (!shouldStop.get()) {
                LOGGER.info("start download {} savePath={}", repoPath, saveDir.getAbsolutePath());
                //after running huggingface-cli download 10 seconds, detect download speed. if speed lower than
                scheduledExecutorService.schedule(() -> {
                    long maxInSpeed = getNetSpeed().stream().max(Comparator.comparingLong(p -> p.getValue()[0]))
                            .map(Pair::getValue).map(arr -> arr[0]).orElse(0L);
                    if (maxInSpeed < MIN_LAUNCH_DOWNLOAD_THRESHOLD_BYTE_SPEED) {
                        LOGGER.error("after launch ,download speed too slow,exit||now={} byte/s", maxInSpeed);
                        shouldStop.set(true);
                    }
                }, 10, TimeUnit.SECONDS);
                CommandExecutor.CommandResult result = CommandExecutor.executeCommandWithoutRefreshBash(cmdList,
                        TimeUnit.DAYS.toMillis(1), stringConsumer);
                if (result.isSuccess()) {
                    LOGGER.info("repoPath {} download finished||savePath={}", repoPath, saveDir.getAbsolutePath());
                    shouldStop.set(true);
                }
            }

        } finally {
            LOGGER.info("stop download task repoPath={}", repoPath);
            try {
                killDownloadProcess(repoPath);
            } catch (Exception e) {
                LOGGER.error("kill process error", e);
            }
            CompletableFutureUtil.shutdownGracefully(scheduledExecutorService, 30, TimeUnit.SECONDS);
        }

    }

    public static String extractCommandProcessId(String command, long timeoutMillis) {
        CommandExecutor.CommandResult result = CommandExecutor.executeCommand(command, timeoutMillis);
        if (!result.isSuccess()) {
            LOGGER.error("execute cmd {} errorInfo={}", command, result.getStdErrorOutput());
            return null;
        }
        if (StringUtils.isBlank(result.getStdOutput())) {
            return null;
        }
        return result.getStdOutput().split("\n")[0].split("\\s+")[1];
    }

    private static void killDownloadProcess(String repoPath) {
        LOGGER.info("kill download repoPath={}", repoPath);
        String command = String.format("ps -ef | grep %s | grep %s|grep -v grep",
                HUGGING_COMMAND, repoPath);
        String processId = extractCommandProcessId(command, TimeUnit.SECONDS.toMillis(5));
        if (StringUtils.isNotBlank(processId)) {
            CommandExecutor.executeCommandWithoutRefreshBash(Lists.newArrayList("kill", "-9", processId), TimeUnit.SECONDS.toMillis(20));
        }
    }


    public static void main(String[] args) {
        String repoPath = "Qwen/Qwen3-235B-A22B-Thinking-2507";
        File savePath = new File("/home/zbj/vllm/Qwen3-235B-A22B-Thinking-2507");
        RetryHuggingDownload retryHuggingDownload = new RetryHuggingDownload();
        retryHuggingDownload.downloadHuggingWholeRepo(repoPath, savePath);
    }

}
