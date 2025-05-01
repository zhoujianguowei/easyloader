package zbj.gr.easy.downloader;

import java.text.DecimalFormat;

public class FileSizeFormatter {
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};
    private static final String[] SPEED_UNITS = {"B/s", "KB/s", "MB/s", "GB/s", "TB/s"};

    public static String formatFileSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("File size cannot be negative");
        }
        if (bytes == 0) {
            return "0 B";
        }

        int unitIndex = (int) (Math.log10(bytes) / Math.log10(1024));
        double value = bytes / Math.pow(1024, unitIndex);
        DecimalFormat decimalFormat = new DecimalFormat("#.00");
        return decimalFormat.format(value) + " " + UNITS[unitIndex];

    }

    public static String formatSpeed(double speed) {
        if (speed < 0) {
            throw new IllegalArgumentException("File size cannot be negative");
        }
        if (speed == 0) {
            return "0B/s";
        }

        int unitIndex = (int) (Math.log10(speed) / Math.log10(1024));
        double value = speed / Math.pow(1024, unitIndex);
        DecimalFormat decimalFormat = new DecimalFormat("#.00");
        return decimalFormat.format(value) + " " + SPEED_UNITS[unitIndex];
    }
}