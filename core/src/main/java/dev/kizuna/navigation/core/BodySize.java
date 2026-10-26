package dev.kizuna.navigation.core;

public record BodySize(double width, double height) {
    public BodySize {
        // 拒绝无效尺寸，碰撞采样必须始终拥有有限正体积。
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("实体尺寸必须为有限正数");
        }
    }
}
