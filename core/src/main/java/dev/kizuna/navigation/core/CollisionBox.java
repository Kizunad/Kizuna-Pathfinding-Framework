package dev.kizuna.navigation.core;

import java.util.Objects;

/** 方块局部坐标中的碰撞箱；允许栅栏等形状高于一格。 */
public record CollisionBox(MotionPoint min, MotionPoint max) {
    public static final CollisionBox FULL = new CollisionBox(new MotionPoint(0, 0, 0), new MotionPoint(1, 1, 1));

    public CollisionBox {
        // 拒绝退化或反向的形状，保证相交与顶面计算使用同一几何含义。
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() >= max.x() || min.y() >= max.y() || min.z() >= max.z()) {
            throw new IllegalArgumentException("碰撞箱必须具有正体积");
        }
    }

    public static CollisionBox floor(double height) {
        return new CollisionBox(new MotionPoint(0, 0, 0), new MotionPoint(1, height, 1));
    }
}
