package dev.kizuna.navigation.core;

import java.util.Objects;
import java.util.Set;

public record NavProfile(BodySize body, MovementLimits limits, Set<MovementPermission> permissions) {
    public NavProfile(BodySize body, MovementLimits limits, Set<MovementPermission> permissions) {
        // 复制权限集合，参数比较和段开始前的变化检查不受外部修改影响。
        this.body = Objects.requireNonNull(body, "body");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.permissions = Set.copyOf(permissions);
    }

    public boolean permits(MovementPermission permission) {
        return permissions.contains(permission);
    }
}
