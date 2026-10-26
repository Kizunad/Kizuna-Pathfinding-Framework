package dev.kizuna.navigation.core;

import java.util.Objects;

public record GridBounds(GridPos min, GridPos max) {
    public GridBounds {
        // 边界包含两端，拒绝反向范围以免空间检查出现空洞。
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("网格边界必须递增");
        }
    }

    public boolean contains(GridPos position) {
        // 三个轴都使用包含端点的范围，与快照索引约定一致。
        int x = position.x();
        int y = position.y();
        int z = position.z();
        return x >= min.x() && x <= max.x()
                && y >= min.y() && y <= max.y()
                && z >= min.z() && z <= max.z();
    }
}
