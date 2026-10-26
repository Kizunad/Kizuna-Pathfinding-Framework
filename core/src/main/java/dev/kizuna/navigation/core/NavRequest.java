package dev.kizuna.navigation.core;

import java.util.List;
import java.util.Objects;

public record NavRequest(GridPos start, NavGoal goal, NavProfile profile,
                         List<CostLayer> costLayers, SearchBudget budget) {
    public NavRequest(GridPos start, NavGoal goal, NavProfile profile,
                      List<CostLayer> costLayers, SearchBudget budget) {
        // 固定本次请求的参数和代价层顺序，防止排队期间被调用方改写。
        this.start = Objects.requireNonNull(start, "start");
        this.goal = Objects.requireNonNull(goal, "goal");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.costLayers = List.copyOf(costLayers);
    }
}
