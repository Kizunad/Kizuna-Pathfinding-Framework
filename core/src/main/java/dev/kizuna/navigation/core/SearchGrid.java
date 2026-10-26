package dev.kizuna.navigation.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单次规划的惰性缓存和工作额度，所有能力通过同一个入口读网格。 */
final class SearchGrid implements VoxelGrid {
    private final VoxelGrid source;
    private final int maxWork;
    private final Map<GridPos, Cell> cells = new HashMap<>();
    private int visitedNodes;

    SearchGrid(VoxelGrid source, SearchBudget budget) {
        this.source = Objects.requireNonNull(source, "source");
        maxWork = budget.maxNodes();
    }

    @Override
    public GridBounds bounds() {
        return source.bounds();
    }

    @Override
    public Cell cellAt(GridPos position) {
        // 边界外无需访问世界；边界内每个位置只在首次使用时读取。
        if (!bounds().contains(position)) {
            return Cell.SOLID;
        }
        Cell cached = cells.get(position);
        if (cached != null) {
            return cached;
        }
        requireWork(visitedNodes, cells.size() + 1);
        Cell cell = Objects.requireNonNull(source.cellAt(position), "cell");
        cells.put(position, cell);
        return cell;
    }

    void visitNode() {
        requireWork(visitedNodes + 1, cells.size());
        visitedNodes++;
    }

    NavResult result(SearchStatus status, Optional<NavPlan> plan) {
        return new NavResult(status, plan, visitedNodes, cells.size());
    }

    private void requireWork(int nodes, int reads) {
        // 在实际读世界或扩展节点之前检查额度，耗尽时返回明确的预算状态。
        if (SearchBudget.workUnits(nodes, reads) > maxWork) {
            throw new Exhausted();
        }
    }

    static final class Exhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private Exhausted() {
            super(null, null, false /* enableSuppression */, false /* writableStackTrace */);
        }
    }
}
