package dev.kizuna.navigation.core;

import java.util.List;
import java.util.Objects;

public record NavPlan(GridPos start, List<MoveEdge> edges, double cost) {
    public NavPlan(GridPos start, List<MoveEdge> edges, double cost) {
        // 复制路径并验证连续性，执行器只接收可按序跟随的有向段。
        this.start = Objects.requireNonNull(start, "start");
        this.edges = List.copyOf(edges);
        this.cost = cost;
        if (!Double.isFinite(cost) || cost < 0) {
            throw new IllegalArgumentException("路径代价必须为有限非负数");
        }
        GridPos previous = start;
        for (MoveEdge edge : edges) {
            if (!edge.from().equals(previous)) {
                throw new IllegalArgumentException("路径移动段必须连续");
            }
            previous = edge.to();
        }
    }

    public GridPos end() {
        // 零段计划以起点作为终点，支持实体已经位于目标的请求。
        if (edges.isEmpty()) {
            return start;
        }
        return edges.get(edges.size() - 1).to();
    }
}
