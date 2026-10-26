package dev.kizuna.navigation.core;

import java.util.Objects;

public record MoveEdge(GridPos from, GridPos to, MoveKind kind, double baseCost, MoveEndpoints endpoints) {
    public MoveEdge {
        // 同时约束节点距离与真实端点距离，保证启发下界和斜向成本成立。
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(endpoints, "endpoints");
        double distance = from.distanceTo(to);
        if (distance == 0 || !Double.isFinite(baseCost) || baseCost < Math.max(distance, endpoints.distance())) {
            throw new IllegalArgumentException("移动代价须为有限数且不小于两端直线距离");
        }
    }

    public MoveEdge(GridPos from, GridPos to, MoveKind kind, double baseCost) {
        this(from, to, kind, baseCost, new MoveEndpoints(from.center(), to.center()));
    }

    public MoveKey key() {
        return new MoveKey(from, to, kind);
    }
}
