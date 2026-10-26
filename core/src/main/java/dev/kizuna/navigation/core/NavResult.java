package dev.kizuna.navigation.core;

import java.util.Objects;
import java.util.Optional;

public record NavResult(SearchStatus status, Optional<NavPlan> plan, int visitedNodes, int readCells) {
    public NavResult {
        // 成功状态必须携带路径，节点与读取计数均不能为负。
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(plan, "plan");
        if ((status == SearchStatus.SUCCESS) != plan.isPresent() || visitedNodes < 0 || readCells < 0) {
            throw new IllegalArgumentException("搜索状态与路径不一致");
        }
    }

    public NavResult(SearchStatus status, Optional<NavPlan> plan, int visitedNodes) {
        this(status, plan, visitedNodes, 0);
    }

    public int workUnits() {
        return SearchBudget.workUnits(visitedNodes, readCells);
    }

    public static NavResult failure(SearchStatus status, int visitedNodes) {
        return new NavResult(status, Optional.empty(), visitedNodes);
    }
}
