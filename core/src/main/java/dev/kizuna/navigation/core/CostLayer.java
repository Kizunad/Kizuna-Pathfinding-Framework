package dev.kizuna.navigation.core;

/** 线程安全、只读快照；返回非负附加代价，正无穷表示不可通行。 */
@FunctionalInterface
public interface CostLayer {
    double cost(NodeContext context);

    default SamplingPrecision precision() {
        return SamplingPrecision.BLOCK;
    }
}
