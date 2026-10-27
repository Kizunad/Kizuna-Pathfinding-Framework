package dev.kizuna.navigation.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

public final class AStarPlanner {
    private final List<MoveCapability> capabilities;

    public AStarPlanner(List<MoveCapability> capabilities) {
        // 同一移动类型只接受一个生成器，避免候选边语义受登记顺序覆盖。
        this.capabilities = List.copyOf(capabilities);
        Set<MoveKind> kinds = new HashSet<>();
        for (MoveCapability capability : capabilities) {
            MoveKind kind = capability.kind();
            if (!kinds.add(kind)) {
                throw new IllegalArgumentException("移动类型重复登记：" + kind.id());
            }
        }
    }

    public NavResult plan(VoxelGrid grid, NavRequest request) {
        return plan(grid, request, Set.of());
    }

    public NavResult plan(VoxelGrid grid, NavRequest request, Set<MoveKey> rejectedMoves) {
        // 惰性读取与节点扩展共用额度，能力和代价层无法绕过世界读取预算。
        SearchGrid searchGrid = new SearchGrid(grid, request.budget());
        try {
            return search(searchGrid, request, rejectedMoves);
        } catch (SearchGrid.Exhausted exhausted) {
            return searchGrid.result(SearchStatus.BUDGET_EXHAUSTED, Optional.empty());
        }
    }

    private NavResult search(SearchGrid grid, NavRequest request, Set<MoveKey> rejectedMoves) {
        // 按稳定的优先级扩展，过期队列项不重复占用节点额度。
        GridPos startPosition = request.start();
        NavGoal goal = request.goal();
        SearchBudget budget = request.budget();
        ExpansionContext start = new ExpansionContext(grid, startPosition, request.profile());
        if (!validStart(start)) {
            return grid.result(SearchStatus.INVALID_START, Optional.empty());
        }
        if (goal.reached(startPosition)) {
            NavPlan plan = new NavPlan(startPosition, List.of(), 0);
            return grid.result(SearchStatus.SUCCESS, Optional.of(plan));
        }
        double minimumDistance = goal.distanceLowerBound(startPosition);
        if (!Double.isFinite(minimumDistance) || minimumDistance < 0) {
            throw new IllegalArgumentException("目标距离下界必须为有限非负数");
        }
        if (minimumDistance > budget.maxDistance()) {
            return grid.result(SearchStatus.GOAL_OUT_OF_RANGE, Optional.empty());
        }
        return searchReachable(grid, request, rejectedMoves);
    }

    private NavResult searchReachable(SearchGrid grid, NavRequest request, Set<MoveKey> rejectedMoves) {
        // 在已验证的起点与距离范围内扩展，保留稳定排序和完整路径重建。
        Search search = new Search(request);
        while (!search.open.isEmpty()) {
            Entry current = search.open.poll();
            GridPos currentPosition = current.position();
            if (current.cost() > search.costs.getOrDefault(currentPosition, Double.POSITIVE_INFINITY)) {
                continue;
            }
            grid.visitNode();
            if (request.goal().reached(currentPosition)) {
                NavPlan plan = reconstruct(request.start(), current, search.previous);
                return grid.result(SearchStatus.SUCCESS, Optional.of(plan));
            }
            expand(grid, request, rejectedMoves, current, search);
        }
        return grid.result(SearchStatus.NO_PATH, Optional.empty());
    }

    private void expand(VoxelGrid grid, NavRequest request, Set<MoveKey> rejected, Entry current, Search search) {
        // 验证能力输出并叠加只读代价层，只保留成本更低的到达方式。
        GridPos origin = current.position();
        NavProfile profile = request.profile();
        ExpansionContext context = new ExpansionContext(grid, origin, profile);
        for (MoveCapability capability : capabilities) {
            for (MoveEdge edge : capability.successors(context)) {
                GridPos target = edge.to();
                if (!edge.from().equals(origin) || !edge.kind().equals(capability.kind())) {
                    throw new IllegalArgumentException("能力生成了来源或类型不匹配的移动段");
                }
                if (rejected.contains(edge.key()) || !grid.bounds().contains(target)
                        || request.start().distanceTo(target) > request.budget().maxDistance()) {
                    continue;
                }
                double extraCost = extraCost(new NodeContext(grid, profile, edge), request.costLayers());
                double totalCost = current.cost() + edge.baseCost() + extraCost;
                if (totalCost < search.costs.getOrDefault(target, Double.POSITIVE_INFINITY)) {
                    search.offer(edge, totalCost);
                }
            }
        }
    }

    private static double extraCost(NodeContext context, List<CostLayer> layers) {
        // 禁止负代价和 NaN，正无穷保留为明确的通行禁令。
        double total = 0;
        for (CostLayer layer : layers) {
            double cost = layer.cost(context);
            if (Double.isNaN(cost) || cost < 0) {
                throw new IllegalArgumentException("代价层返回了负数或 NaN");
            }
            total += cost;
        }
        return total;
    }

    private static boolean validStart(ExpansionContext context) {
        // 地面、水中和攀爬分别确认支撑或权限，再验证实际身体净空。
        GridPos origin = context.origin();
        NavProfile profile = context.profile();
        Terrain terrain = context.grid().cellAt(origin).terrain();
        boolean supported = VoxelGeometry.canStand(context, origin);
        boolean swimming = terrain == Terrain.WATER && profile.permits(MovementPermission.SWIM);
        boolean climbing = terrain == Terrain.CLIMBABLE && profile.permits(MovementPermission.CLIMB);
        return (supported || swimming || climbing)
                && VoxelGeometry.canOccupy(context, VoxelGeometry.feet(context, origin));
    }

    private static NavPlan reconstruct(GridPos start, Entry current, Map<GridPos, MoveEdge> previous) {
        // 沿前驱链逆向恢复后翻转，保留执行器需要的有向移动段。
        List<MoveEdge> edges = new ArrayList<>();
        GridPos cursor = current.position();
        while (!cursor.equals(start)) {
            MoveEdge edge = previous.get(cursor);
            edges.add(edge);
            cursor = edge.from();
        }
        Collections.reverse(edges);
        return new NavPlan(start, edges, current.cost());
    }

    private record Entry(GridPos position, double cost, double estimate, long sequence) {
        double priority() {
            return cost + estimate;
        }
    }

    private static final class Search {
        private final NavRequest request;
        private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::priority)
                .thenComparingDouble(Entry::estimate)
                .thenComparingLong(Entry::sequence));
        private final Map<GridPos, Double> costs = new HashMap<>();
        private final Map<GridPos, MoveEdge> previous = new HashMap<>();
        private long sequence;

        private Search(NavRequest request) {
            // 起点以零成本入队，插入序号用于同代价时的确定性排序。
            this.request = request;
            GridPos start = request.start();
            costs.put(start, 0.0);
            open.add(new Entry(start, 0, estimate(start), sequence++));
        }

        private void offer(MoveEdge edge, double cost) {
            // 前驱和最低成本同时更新，保证重建路径与队列中的候选一致。
            GridPos target = edge.to();
            costs.put(target, cost);
            previous.put(target, edge);
            open.add(new Entry(target, cost, estimate(target), sequence++));
        }

        private double estimate(GridPos position) {
            // 自定义目标也必须提供有限非负的下界，避免破坏优先队列。
            double estimate = request.goal().lowerBound(position);
            if (!Double.isFinite(estimate) || estimate < 0) {
                throw new IllegalArgumentException("启发值必须为有限非负数");
            }
            return estimate;
        }
    }
}
