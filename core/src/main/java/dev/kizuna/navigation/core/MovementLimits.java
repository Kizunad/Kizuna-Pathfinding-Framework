package dev.kizuna.navigation.core;

public record MovementLimits(int stepHeight, int jumpHeight, int safeDrop, int maxGap) {
    public static final int MAX_LIMIT = 32;

    public MovementLimits {
        // 统一限制各类位移边界，台阶能力不能超过声明的跳高能力。
        validateLimit(stepHeight);
        validateLimit(jumpHeight);
        validateLimit(safeDrop);
        validateLimit(maxGap);
        if (jumpHeight < stepHeight) {
            throw new IllegalArgumentException("跳高不能小于台阶高度");
        }
    }

    private static void validateLimit(int limit) {
        // 限制弹道和邻列扫描的最大范围，拒绝负值与不支持的超大能力。
        if (limit < 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("移动上限须在 0 到 " + MAX_LIMIT + " 之间");
        }
    }
}
