package dev.kizuna.navigation.core;

import java.util.Objects;

/** 移动两端的实际脚底位置，与整数节点的标识分离。 */
public record MoveEndpoints(MotionPoint from, MotionPoint to) {
    public MoveEndpoints {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public double distance() {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
