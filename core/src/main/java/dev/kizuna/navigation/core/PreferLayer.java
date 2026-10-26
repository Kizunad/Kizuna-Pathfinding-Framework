package dev.kizuna.navigation.core;

import java.util.Objects;
import java.util.function.ToDoubleFunction;

/** 打分须在 [0, 1] 内；1 表示最偏好，其他位置增加非负代价。 */
public record PreferLayer(ToDoubleFunction<GridPos> score, double weight) implements CostLayer {
    public PreferLayer {
        // 限制权重为非负数，偏好通过增加惩罚表达而不削弱 A* 下界。
        Objects.requireNonNull(score, "score");
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException("偏好权重须为有限非负数");
        }
    }

    @Override
    public double cost(NodeContext context) {
        // 将最高偏好映射为零惩罚，其他分值只能增加移动代价。
        double value = score.applyAsDouble(context.edge().to());
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("偏好打分须在 0 到 1 之间");
        }
        return (1 - value) * weight;
    }
}
