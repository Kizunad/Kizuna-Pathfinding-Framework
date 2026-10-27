package dev.kizuna.navigation.core;

/** anchor 仅表示目标；距离下界用于范围剪枝，代价下界用于 A* 排序。 */
public interface NavGoal {
    GridPos anchor();

    boolean reached(GridPos position);

    default double distanceLowerBound(GridPos position) {
        // 自定义目标未提供几何保证时不剪枝，不能用代价下界代替距离。
        return 0;
    }

    default double lowerBound(GridPos position) {
        // 路径代价不小于移动距离，几何下界也可以作为保守启发值。
        return distanceLowerBound(position);
    }
}
