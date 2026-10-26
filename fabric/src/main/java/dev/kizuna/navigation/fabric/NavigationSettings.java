package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.SearchBudget;

import java.util.Objects;

public record NavigationSettings(SearchBudget requestBudget, int nodesPerTick,
                                 int maxQueuedRequests, RecoverySettings recovery) {
    public static final int MAX_NEAR_DISTANCE = 32;
    public static final int DEFAULT_NODE_BUDGET = 4000;
    private static final int DEFAULT_QUEUE_LIMIT = 256;
    public static final NavigationSettings DEFAULT = new NavigationSettings(
            new SearchBudget(DEFAULT_NODE_BUDGET, MAX_NEAR_DISTANCE), DEFAULT_NODE_BUDGET,
            DEFAULT_QUEUE_LIMIT, RecoverySettings.DEFAULT);

    public NavigationSettings {
        // 单次请求必须能装入一个 tick 的总额度，距离不得超出近距离模型。
        Objects.requireNonNull(requestBudget, "requestBudget");
        Objects.requireNonNull(recovery, "recovery");
        if (nodesPerTick <= 0 || maxQueuedRequests <= 0 || requestBudget.maxNodes() > nodesPerTick
                || requestBudget.maxDistance() > MAX_NEAR_DISTANCE) {
            throw new IllegalArgumentException("近距离预算或队列设置无效");
        }
    }
}
