package dev.kizuna.navigation.fabric;

public record RecoverySettings(int stuckTicks, int retryDelayTicks, int maxReplans, double minProgress) {
    private static final int DEFAULT_STUCK_TICKS = 40;
    private static final int DEFAULT_RETRY_DELAY = 10;
    private static final int DEFAULT_REPLANS = 4;
    private static final double DEFAULT_MIN_PROGRESS = 0.05;
    public static final RecoverySettings DEFAULT = new RecoverySettings(DEFAULT_STUCK_TICKS,
            DEFAULT_RETRY_DELAY, DEFAULT_REPLANS, DEFAULT_MIN_PROGRESS);

    public RecoverySettings {
        // 恢复时间和位移阈值必须可执行，零次重算仍是合法的关闭恢复配置。
        if (stuckTicks <= 0 || retryDelayTicks <= 0 || maxReplans < 0
                || !Double.isFinite(minProgress) || minProgress <= 0) {
            throw new IllegalArgumentException("恢复设置无效");
        }
    }
}
