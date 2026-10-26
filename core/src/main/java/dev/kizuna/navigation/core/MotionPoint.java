package dev.kizuna.navigation.core;

public record MotionPoint(double x, double y, double z) {
    public static final double BLOCK_CENTER = 0.5;

    public MotionPoint {
        // 拒绝非有限坐标，避免弹道积分和碰撞比较静默失效。
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("位置或速度分量必须为有限数");
        }
    }
}
