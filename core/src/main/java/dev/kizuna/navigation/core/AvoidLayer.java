package dev.kizuna.navigation.core;

import java.util.List;

public record AvoidLayer(List<GridPos> points, double radius, double weight) implements CostLayer {
    public AvoidLayer(List<GridPos> points, double radius, double weight) {
        // 复制避让点并限制权重，避免可变输入或负惩罚改变搜索含义。
        this.points = List.copyOf(points);
        this.radius = radius;
        this.weight = weight;
        if (!Double.isFinite(radius) || !Double.isFinite(weight) || radius <= 0 || weight < 0) {
            throw new IllegalArgumentException("避让半径须为正数，权重须为非负数");
        }
    }

    @Override
    public double cost(NodeContext context) {
        // 半径内按距离线性叠加惩罚，半径外不改变路线代价。
        double penalty = 0;
        for (GridPos point : points) {
            double distance = context.edge().to().distanceTo(point);
            penalty += Math.max(0, 1 - distance / radius) * weight;
        }
        return penalty;
    }
}
