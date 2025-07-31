package zbj.gr.easy.video2x;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Pair;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.grw.xiaobai.util.CommandExecutor;
import com.grw.xiaobai.util.CompletableFutureUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class VideoEnhance {
    private static final String VIDEO2X_COMMAND = "video2x";
    private static final String CPU_TYPE = "cpu";
    private static final String GPU_TYPE = "gpu";
    private static final long SHORT_COMMAND_TIMEOUT = TimeUnit.SECONDS.toMillis(10);
    private static final long CONVERT_SINGLE_VIDEO_TIMEOUT = TimeUnit.HOURS.toMillis(12);
    private static Map<Integer, Integer> gpuIndex2LoadMap = Maps.newTreeMap();
    private static final int SINGLE_GPU_MAX_TASK_NUM = 3;
    private static List<Pair<Integer, String>> DEVICE_LIST;
    private static final Logger LOGGER = LoggerFactory.getLogger(VideoEnhance.class);

    static {
        checkVideo2xInstalled();
    }

    private static void checkVideo2xInstalled() {
        CommandExecutor.CommandResult commandResult = CommandExecutor.executeCommandWithoutRefreshBash(Lists.newArrayList("which", VIDEO2X_COMMAND), SHORT_COMMAND_TIMEOUT);
        Assert.isTrue(commandResult.isSuccess(), "failed to execute command check video2x");
        Assert.isTrue(StringUtils.isNotBlank(commandResult.getStdOutput()), "video2x not installed");
    }

    private static List<Pair<Integer, String>> deviceList() {
        if (DEVICE_LIST != null) {
            return DEVICE_LIST;
        }
        synchronized (VideoEnhance.class) {
            if (DEVICE_LIST != null) {
                return DEVICE_LIST;
            }
            CommandExecutor.CommandResult result = CommandExecutor.executeCommandWithoutRefreshBash(Lists.newArrayList(VIDEO2X_COMMAND, "-l"), SHORT_COMMAND_TIMEOUT);
            Assert.isTrue(result.isSuccess());
            String originDeviceResult = result.getStdOutput();
            String[] strArrays = originDeviceResult.split(System.lineSeparator());
            int i = 0;
            DEVICE_LIST = Lists.newArrayList();
            Pattern pattern = Pattern.compile("^(\\d+)\\.\\s+");
            for (; i < strArrays.length; i++) {
                Matcher matcher = pattern.matcher(strArrays[i]);
                if (!matcher.find()) {
                    continue;
                }
                Integer index = Integer.parseInt(matcher.group(1));
                i++;
                while (i < strArrays.length) {
                    if (strArrays[i].toLowerCase().contains(CPU_TYPE)) {
                        DEVICE_LIST.add(new Pair<>(index, CPU_TYPE));
                        break;
                    } else if (strArrays[i].toLowerCase().contains(GPU_TYPE)) {
                        DEVICE_LIST.add(new Pair<>(index, GPU_TYPE));
                        break;
                    }
                    i++;
                }
            }
        }
        return DEVICE_LIST;
    }

    private static Integer acquireLowLoadGpu() {
        List<Integer> targetGpuIndexList = deviceList().stream().filter(pair -> pair.getValue().equals(GPU_TYPE)).map(Pair::getKey).collect(Collectors.toList());
        Integer targetLowLoadGpuIndex;
        synchronized (VideoEnhance.class) {
            if (MapUtils.isEmpty(gpuIndex2LoadMap)) {
                targetGpuIndexList.forEach(index -> gpuIndex2LoadMap.put(index, 0));
            }
            int minTaskNum = gpuIndex2LoadMap.entrySet().stream().map(Map.Entry::getValue).min(Integer::compareTo).orElse(0);
            if (minTaskNum >= SINGLE_GPU_MAX_TASK_NUM) {
                LOGGER.error("gpu load too high||single gpu max task num={}", SINGLE_GPU_MAX_TASK_NUM);
                return null;
            } else {
                targetLowLoadGpuIndex = gpuIndex2LoadMap.entrySet().stream().filter(var -> var.getValue() == minTaskNum).map(Map.Entry::getKey).sorted().findFirst().orElse(null);
            }
            gpuIndex2LoadMap.compute(targetLowLoadGpuIndex, (k, v) -> v == null ? 1 : v + 1);
        }
        return targetLowLoadGpuIndex;
    }

    private static void releaseGpu(int gpuIndex) {
        synchronized (VideoEnhance.class) {
            gpuIndex2LoadMap.compute(gpuIndex, (k, v) -> v - 1);
        }
    }

    public static boolean convertSingleVideo(File inputFile, File outputFile, int scale) {
        Integer lowLoadGpuIndex = acquireLowLoadGpu();
        if (inputFile.getAbsolutePath().contains(" ") || outputFile.getAbsolutePath().contains(" ")) {
            LOGGER.error("input or output video path contains white space");
            throw new RuntimeException("path contains white space");
        }
        if (lowLoadGpuIndex == null) {
            throw new RuntimeException("no free gpu");
        }
        LOGGER.info("video {} use gpu device {}", inputFile.getName(), lowLoadGpuIndex);
        String convertJoinCommand = String.format("video2x -i %s -o %s -p  realesrgan -s %s  -d %s -n 1",
                inputFile.getAbsolutePath(), outputFile.getAbsolutePath(), scale, lowLoadGpuIndex);
        Consumer<String> consumer = var -> {
            LOGGER.info("file {} convert data info {} || gpu index={}", inputFile.getName(), var, lowLoadGpuIndex);
        };
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            CommandExecutor.CommandResult result = CommandExecutor.executeCommandWithoutRefreshBash(Arrays.asList(convertJoinCommand.split("\\s+")), CONVERT_SINGLE_VIDEO_TIMEOUT, consumer);
            LOGGER.info("convert {} total cost={}", inputFile.getName(), stopwatch.elapsed(TimeUnit.MINUTES));
            if (!result.isSuccess()) {
                LOGGER.error("failed to convert file={}", inputFile.getName());
            } else {
                LOGGER.info("file {} convert success||savePath={}", inputFile.getName(), outputFile.getAbsolutePath());
            }
            return result.isSuccess();
        } finally {
            releaseGpu(lowLoadGpuIndex);
        }
    }

    public static int[] convertDir(File inputDir, int scale) {
        File outputDir = new File(inputDir.getParent(), String.format("enhanced_%s", inputDir.getName()));
        List<String> videoNameExtensionList = Lists.newArrayList("mp4", "mkv", "ts", "rmvb");
        return convertDir(inputDir, outputDir, scale, videoNameExtensionList);
    }

    public static int[] convertDir(File inputDir, File outputDir, int scale, List<String> nameExtensionList) {
        List<Integer> gpuIndexList = deviceList().stream().filter(var -> var.getValue().equals(GPU_TYPE)).map(Pair::getKey).collect(Collectors.toList());
        LOGGER.info("total gpu index list={}", gpuIndexList);
        int[] result = new int[]{0, 0};
        if (CollectionUtils.isEmpty(gpuIndexList)) {
            LOGGER.error("no gpu");
            return result;
        }
        if (!outputDir.exists() || !outputDir.isDirectory()) {
            Assert.isTrue(outputDir.mkdirs(), String.format("failed to create output dir {}", outputDir.getAbsolutePath()));
        }
        List<File> fileVideoList = (List<File>) FileUtils.listFiles(inputDir, nameExtensionList.toArray(new String[0]), true);
        LOGGER.info("scan dir {} total video size={}", inputDir.getAbsolutePath(), fileVideoList.size());
        AtomicInteger successAtomic = new AtomicInteger();
        AtomicInteger failAtomic = new AtomicInteger();
        ExecutorService executorService = Executors.newFixedThreadPool(gpuIndexList.size() * SINGLE_GPU_MAX_TASK_NUM);
        CompletableFutureUtil.asyncRunAllOf(fileVideoList, inputVideo -> {
            String relativePath = inputVideo.getAbsolutePath().substring(inputDir.getAbsolutePath().length());
            if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
                relativePath = relativePath.substring(1);
            }
            File outputVideo = Paths.get(outputDir.getAbsolutePath(), relativePath).toFile();
            boolean convertResult = convertSingleVideo(inputVideo, outputVideo, scale);
            if (convertResult) {
                successAtomic.incrementAndGet();
            } else {
                failAtomic.incrementAndGet();
            }
        }, executorService).join();
        LOGGER.info("convert {} finished||success count={}||fail count={}", inputDir.getAbsolutePath(), successAtomic.get(), failAtomic.get());
        result[0] = successAtomic.get();
        result[1] = failAtomic.get();
        CompletableFutureUtil.shutdownGracefully(executorService, 10, TimeUnit.SECONDS);
        return result;
    }
}
