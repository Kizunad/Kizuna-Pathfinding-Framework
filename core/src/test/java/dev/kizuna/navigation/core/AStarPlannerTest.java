package dev.kizuna.navigation.core;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AStarPlannerTest {
    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.9;
    private static final int JUMP_HEIGHT = 2;
    private static final int GAP_WIDTH = 3;
    private static final int DROP_LIMIT = 3;
    private static final int NODE_BUDGET = 4000;
    private static final int SEARCH_RANGE = 32;
    private static final int LOWER_BOUND = -8;
    private static final int UPPER_BOUND = 12;
    private static final int DETOUR_Z = 2;
    private static final int PLATFORM_END = 6;
    private static final double PATH_HEIGHT = 15.0 / 16;
    private static final double SLAB_HEIGHT = 0.5;
    private static final double TOLERANCE = 0.00001;
    private static final double WIDE_WIDTH = 1.6;
    private static final int REPEAT_COUNT = 20;
    private static final int CEILING_HEIGHT = 3;
    private static final int BARRIER_HEIGHT = 5;
    private static final int[][] DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final GridPos START = new GridPos(0, 1, 0);
    private static final NavProfile PROFILE = profile(new MovementLimits(1, JUMP_HEIGHT, DROP_LIMIT, GAP_WIDTH));
    private final AStarPlanner planner = new AStarPlanner(BuiltInCapabilities.all());

    @Test
    public void climbsOneBlockStep() {
        // 单个相邻高台只允许台阶段到达，避免绕路掩盖能力分类。
        Grid fixture = new Grid();
        fixture.set(START.offset(0, -1, 0), Cell.SOLID);
        fixture.column(1, 0, 1);
        NavPlan plan = success(fixture, new GridPos(1, JUMP_HEIGHT, 0), PROFILE);
        assertEquals(MoveKind.STEP_UP, plan.edges().get(0).kind());
    }

    @Test
    public void jumpsTwoBlocksUpAndHonorsHeadroom() {
        // 同一高台先验证可达，再封闭起跳净空确认必须拒绝。
        Grid fixture = new Grid();
        fixture.set(START.offset(0, -1, 0), Cell.SOLID);
        fixture.column(1, 0, JUMP_HEIGHT);
        GridPos target = START.offset(1, JUMP_HEIGHT, 0);
        NavPlan plan = success(fixture, target, PROFILE);
        assertEquals(MoveKind.JUMP_UP, plan.edges().get(0).kind());
        fixture.set(START.offset(0, JUMP_HEIGHT, 0), Cell.SOLID);
        assertEquals(SearchStatus.NO_PATH, search(fixture, target, PROFILE).status());
    }

    @Test
    public void crossesThreeEmptyBlocksOnlyWhenPermitted() {
        // 固定地形只缩小跨沟上限，确认可达性确由移动参数控制。
        Grid fixture = gap();
        GridPos target = START.offset(GAP_WIDTH + 1, 0, 0);
        NavPlan plan = success(fixture, target, PROFILE);
        assertEquals(1, plan.edges().size());
        assertEquals(MoveKind.LEAP, plan.edges().get(0).kind());
        NavProfile shortLeap = profile(new MovementLimits(1, JUMP_HEIGHT, DROP_LIMIT, GAP_WIDTH - 1));
        assertEquals(SearchStatus.NO_PATH, search(fixture, target, shortLeap).status());
    }

    @Test
    public void acceptsSafeDropAndRejectsOneBlockBeyondLimit() {
        // 固定落差测试安全上限两侧，不允许改走更远的跳跃路线。
        Grid fixture = new Grid();
        fixture.column(0, 0, DROP_LIMIT);
        fixture.set(new GridPos(1, 0, 0), Cell.SOLID);
        GridPos start = new GridPos(0, DROP_LIMIT + 1, 0);
        GridPos target = new GridPos(1, 1, 0);
        NavRequest request = request(start, target, PROFILE);
        NavResult result = planner.plan(fixture, request);
        assertEquals(SearchStatus.SUCCESS, result.status());
        assertEquals(MoveKind.DROP, result.plan().orElseThrow().edges().get(0).kind());
        NavProfile unsafe = profile(new MovementLimits(1, JUMP_HEIGHT, DROP_LIMIT - 1, 0));
        assertEquals(SearchStatus.NO_PATH, planner.plan(fixture, request(start, target, unsafe)).status());
    }

    @Test
    public void refusesSafeHeightDropThroughFire() {
        // 落差安全仍不能穿过火焰，危险层必须检查下降过程。
        Grid fixture = new Grid();
        fixture.column(0, 0, DROP_LIMIT);
        fixture.set(new GridPos(1, 0, 0), Cell.SOLID);
        fixture.set(new GridPos(1, DROP_LIMIT, 0), new Cell(Terrain.AIR, Hazard.FIRE));
        GridPos start = new GridPos(0, DROP_LIMIT + 1, 0);
        NavRequest request = request(start, new GridPos(1, 1, 0), PROFILE);
        assertEquals(SearchStatus.NO_PATH, planner.plan(fixture, request).status());
    }

    @Test
    public void avoidsEveryDangerousFloorType() {
        // 禁用跨沟能力后逐一验证危险地面的步行绕路。
        for (Hazard hazard : Hazard.values()) {
            if (hazard == Hazard.NONE) {
                continue;
            }
            Grid fixture = flat();
            GridPos danger = new GridPos(JUMP_HEIGHT, 0, 0);
            fixture.set(danger, new Cell(Terrain.SOLID, hazard));
            NavProfile walking = profile(new MovementLimits(1, JUMP_HEIGHT, DROP_LIMIT, 0));
            NavPlan plan = success(fixture, new GridPos(PLATFORM_END, 1, 0), walking);
            assertFalse(plan.edges().stream().anyMatch(edge -> edge.to().equals(danger.offset(0, 1, 0))));
            assertTrue(plan.edges().stream().anyMatch(edge -> edge.to().z() != 0));
        }
    }

    @Test
    public void repeatsExactlyForIdenticalInputs() {
        // 重复比较完整结果，包含路径、代价和确定性的工作统计。
        Grid fixture = flat();
        GridPos target = new GridPos(PLATFORM_END, 1, DETOUR_Z);
        NavResult expected = search(fixture, target, PROFILE);
        for (int repeat = 0; repeat < REPEAT_COUNT; repeat++) {
            assertEquals(expected, search(fixture, target, PROFILE));
        }
    }

    @Test
    public void allDeclaredJumpHeightsProduceReachableArcs() {
        // 声明范围内的每个跳高都必须产生经过顶点后落向目标的轨迹。
        MotionPoint from = START.center();
        for (int height = JUMP_HEIGHT; height <= MovementLimits.MAX_LIMIT; height++) {
            MotionPoint target = START.offset(1, height, 0).center();
            JumpArc arc = JumpArc.between(from, target);
            List<MotionPoint> points = arc.points();
            assertEquals(target, points.get(points.size() - 1));
            assertTrue(points.stream().mapToDouble(MotionPoint::y).max().orElseThrow() > target.y());
        }
    }

    @Test
    public void boundsSearchAndReportsBudgetExhaustion() {
        // 工作额度耗尽与目标超范围必须返回不同的失败原因。
        Grid fixture = flat();
        GridPos target = new GridPos(PLATFORM_END, 1, 0);
        NavRequest request = new NavRequest(START, NavGoals.point(target), PROFILE, List.of(),
                new SearchBudget(1, SEARCH_RANGE));
        NavResult result = planner.plan(fixture, request);
        assertEquals(SearchStatus.BUDGET_EXHAUSTED, result.status());
        assertEquals(1, result.workUnits());
        NavRequest distant = new NavRequest(START, NavGoals.point(target), PROFILE, List.of(),
                new SearchBudget(NODE_BUDGET, 1));
        assertEquals(SearchStatus.GOAL_OUT_OF_RANGE, planner.plan(fixture, distant).status());
    }

    @Test
    public void respectsBodyWidth() {
        // 一格独木地面不能被宽实体当成完整的脚下支撑。
        Grid fixture = new Grid();
        fixture.floor(0, PLATFORM_END, 0, 0);
        NavProfile wide = new NavProfile(new BodySize(JUMP_HEIGHT, HEIGHT), PROFILE.limits(), Set.of());
        assertEquals(SearchStatus.INVALID_START, search(fixture, START.offset(1, 0, 0), wide).status());
    }

    @Test
    public void customCapabilityAndRejectedSegmentUseSameSearch() {
        // 自定义边复用搜索和否决机制，不能绕过被屏蔽的移动段。
        MoveKind kind = new MoveKind("test:bridge");
        GridPos target = START.offset(GAP_WIDTH + 1, 0, 0);
        MoveEdge edge = new MoveEdge(START, target, kind, GAP_WIDTH + 1);
        MoveCapability custom = new MoveCapability() {
            @Override
            public MoveKind kind() {
                return kind;
            }

            @Override
            public List<MoveEdge> successors(ExpansionContext context) {
                // 只在原始起点提供自定义边，使屏蔽后的无路结果可明确归因。
                if (context.origin().equals(START)) {
                    return List.of(edge);
                }
                return List.of();
            }
        };
        AStarPlanner customPlanner = new AStarPlanner(List.of(custom));
        NavRequest request = request(START, target, PROFILE);
        assertEquals(SearchStatus.SUCCESS, customPlanner.plan(gap(), request).status());
        assertEquals(SearchStatus.NO_PATH, customPlanner.plan(gap(), request, Set.of(edge.key())).status());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeCostLayer() {
        // 负代价破坏启发下界，应立即向调用方报告参数错误。
        NavRequest request = new NavRequest(START, NavGoals.point(START.offset(1, 0, 0)), PROFILE,
                List.of(context -> -1), new SearchBudget(NODE_BUDGET, SEARCH_RANGE));
        planner.plan(flat(), request);
    }

    @Test
    public void snapshotDoesNotRetainMutableGrid() {
        // 离线完整快照应与源数据解耦，边界外始终封闭。
        Grid fixture = flat();
        VoxelSnapshot snapshot = VoxelSnapshot.capture(fixture.bounds(), fixture::cellAt);
        fixture.set(START, Cell.SOLID);
        assertEquals(Cell.AIR, snapshot.cellAt(START));
        assertEquals(Cell.SOLID, snapshot.cellAt(new GridPos(UPPER_BOUND + 1, 0, 0)));
    }

    @Test
    public void doorsWaterAndClimbingRequirePermissions() {
        // 保持地形不变只切换权限，核对三种特殊介质的能力入口。
        Grid fixture = new Grid();
        fixture.floor(0, 1, 0, 0);
        GridPos target = START.offset(1, 0, 0);
        fixture.set(target, new Cell(Terrain.WOOD_DOOR, Hazard.NONE));
        assertEquals(SearchStatus.NO_PATH, search(fixture, target, PROFILE).status());
        NavProfile doors = new NavProfile(PROFILE.body(), PROFILE.limits(), Set.of(MovementPermission.OPEN_DOORS));
        assertEquals(MoveKind.DOOR, success(fixture, target, doors).edges().get(0).kind());
        fixture.set(target, new Cell(Terrain.WATER, Hazard.NONE));
        NavProfile swimmer = new NavProfile(PROFILE.body(), PROFILE.limits(), Set.of(MovementPermission.SWIM));
        assertEquals(MoveKind.SWIM, success(fixture, target, swimmer).edges().get(0).kind());
        fixture.set(START, new Cell(Terrain.CLIMBABLE, Hazard.NONE));
        GridPos upper = START.offset(0, 1, 0);
        fixture.set(upper, new Cell(Terrain.CLIMBABLE, Hazard.NONE));
        NavProfile climber = new NavProfile(PROFILE.body(), PROFILE.limits(), Set.of(MovementPermission.CLIMB));
        assertEquals(MoveKind.CLIMB, success(fixture, upper, climber).edges().get(0).kind());
    }

    @Test
    public void leapsAcrossEveryPermittedFlatGapAndRejectsTheNextWidth() {
        // 每种沟宽只放起落两处支撑，防止绕路或中途落脚掩盖跨度限制。
        for (int gap = 1; gap <= GAP_WIDTH + 1; gap++) {
            Grid fixture = landing(gap, 0, 0);
            GridPos target = START.offset(gap + 1, 0, 0);
            NavResult result = search(fixture, target, PROFILE);
            if (gap <= GAP_WIDTH) {
                assertEquals(SearchStatus.SUCCESS, result.status());
                assertEquals(MoveKind.LEAP, result.plan().orElseThrow().edges().get(0).kind());
            } else {
                assertEquals(SearchStatus.NO_PATH, result.status());
            }
        }
    }

    @Test
    public void leapsAcrossGapOntoOneBlockAndMaximumHeightPlatforms() {
        // 跨沟与跳高必须组合成一段，并严格拒绝高于声明上限的落点。
        for (int rise : new int[]{1, JUMP_HEIGHT}) {
            Grid fixture = landing(GAP_WIDTH, rise, 0);
            GridPos target = START.offset(GAP_WIDTH + 1, rise, 0);
            NavPlan plan = success(fixture, target, PROFILE);
            assertEquals(1, plan.edges().size());
            MoveEdge edge = plan.edges().get(0);
            assertEquals(MoveKind.LEAP, edge.kind());
            assertEquals(target.center(), edge.endpoints().to());
        }
        Grid tooHigh = landing(GAP_WIDTH, JUMP_HEIGHT + 1, 0);
        GridPos target = START.offset(GAP_WIDTH + 1, JUMP_HEIGHT + 1, 0);
        assertEquals(SearchStatus.NO_PATH, search(tooHigh, target, PROFILE).status());
    }

    @Test
    public void leapsDownToSafeDropAndRejectsDeeperLanding() {
        // 只有两岸支撑，确保降低落点时仍遵守安全下落边界。
        for (int depth : new int[]{DROP_LIMIT, DROP_LIMIT + 1}) {
            Grid fixture = landing(GAP_WIDTH, -depth, 0);
            GridPos target = START.offset(GAP_WIDTH + 1, -depth, 0);
            NavResult result = search(fixture, target, PROFILE);
            if (depth == DROP_LIMIT) {
                assertEquals(SearchStatus.SUCCESS, result.status());
                assertEquals(MoveKind.LEAP, result.plan().orElseThrow().edges().get(0).kind());
            } else {
                assertEquals(SearchStatus.NO_PATH, result.status());
            }
        }
    }

    @Test
    public void leapsInAllEightDirectionsWithHeightDifferences() {
        // 同一跨度旋转到八个方向，高低落点与欧氏成本约束均须成立。
        for (int[] direction : DIRECTIONS) {
            for (int rise : new int[]{0, JUMP_HEIGHT, -DROP_LIMIT}) {
                Grid fixture = new Grid();
                fixture.set(START.offset(0, -1, 0), Cell.SOLID);
                int distance = GAP_WIDTH + 1;
                GridPos target = START.offset(direction[0] * distance, rise, direction[1] * distance);
                fixture.set(target.offset(0, -1, 0), Cell.SOLID);
                NavPlan plan = success(fixture, target, PROFILE);
                MoveEdge edge = plan.edges().get(0);
                assertEquals(MoveKind.LEAP, edge.kind());
                assertTrue(edge.baseCost() >= START.distanceTo(target));
            }
        }
    }

    @Test
    public void leapRejectsCeilingAndMidFlightObstacle() {
        // 分别封闭整段顶部和弹道中段，防止仅检查起落点。
        Grid ceiling = gap();
        for (int x = 0; x <= GAP_WIDTH + 1; x++) {
            ceiling.set(START.offset(x, CEILING_HEIGHT, 0), Cell.SOLID);
        }
        GridPos target = START.offset(GAP_WIDTH + 1, 0, 0);
        assertEquals(SearchStatus.NO_PATH, search(ceiling, target, PROFILE).status());
        Grid obstacle = gap();
        obstacle.column(JUMP_HEIGHT, 0, BARRIER_HEIGHT);
        assertEquals(SearchStatus.NO_PATH, search(obstacle, target, PROFILE).status());
    }

    @Test
    public void leapUsesShortBlockTakeoffLandingAndHeadroom() {
        // 起落点都使用矮碰撞，实际端点与净空必须遵循同一个几何模型。
        Grid fixture = gap();
        GridPos target = START.offset(GAP_WIDTH + 1, 0, 0);
        fixture.set(START.offset(0, -1, 0), shortBlock(PATH_HEIGHT));
        fixture.set(target.offset(0, -1, 0), shortBlock(SLAB_HEIGHT));
        NavPlan plan = success(fixture, target, PROFILE);
        MoveEdge edge = plan.edges().get(0);
        assertEquals(PATH_HEIGHT, edge.endpoints().from().y(), TOLERANCE);
        assertEquals(SLAB_HEIGHT, edge.endpoints().to().y(), TOLERANCE);
        fixture.set(START.offset(0, JUMP_HEIGHT, 0), Cell.SOLID);
        assertEquals(SearchStatus.NO_PATH, search(fixture, target, PROFILE).status());
    }

    @Test
    public void leapHeightLimitsIncludeFractionalTakeoffAndLanding() {
        // 整数节点高差看似合规时，矮方块引入的额外落差仍必须计入上限。
        Grid high = landing(GAP_WIDTH, JUMP_HEIGHT, 0);
        high.set(START.offset(0, -1, 0), shortBlock(PATH_HEIGHT));
        GridPos highTarget = START.offset(GAP_WIDTH + 1, JUMP_HEIGHT, 0);
        assertEquals(SearchStatus.NO_PATH, search(high, highTarget, PROFILE).status());
        Grid low = landing(GAP_WIDTH, -DROP_LIMIT, 0);
        GridPos lowTarget = START.offset(GAP_WIDTH + 1, -DROP_LIMIT, 0);
        low.set(lowTarget.offset(0, -1, 0), shortBlock(SLAB_HEIGHT));
        assertEquals(SearchStatus.NO_PATH, search(low, lowTarget, PROFILE).status());
    }

    @Test
    public void halfBlockStepDoesNotRequireJumpHeadroom() {
        // 半格抬脚应能通过只够行走的顶棚，不能误用高弹道拒绝矮台阶。
        Grid fixture = new Grid();
        fixture.set(START.offset(0, -1, 0), shortBlock(SLAB_HEIGHT));
        GridPos target = START.offset(1, 0, 0);
        fixture.set(target.offset(0, -1, 0), Cell.SOLID);
        fixture.set(START.offset(0, JUMP_HEIGHT, 0), Cell.SOLID);
        fixture.set(target.offset(0, JUMP_HEIGHT, 0), Cell.SOLID);
        NavPlan plan = success(fixture, target, PROFILE);
        assertEquals(MoveKind.STEP_UP, plan.edges().get(0).kind());
    }

    @Test
    public void diagonalSweepChecksWidthBetweenValidEndpoints() {
        // 两端对宽实体均可站立，只有中途的侧向障碍会使宽身体无法通过。
        Grid fixture = new Grid();
        fixture.floor(-DETOUR_Z, PLATFORM_END, -DETOUR_Z, PLATFORM_END);
        fixture.set(START.offset(JUMP_HEIGHT, 0, 0), Cell.SOLID);
        GridPos target = START.offset(GAP_WIDTH + 1, 0, GAP_WIDTH + 1);
        ExpansionContext narrow = new ExpansionContext(fixture, START, PROFILE);
        NavProfile wideProfile = new NavProfile(new BodySize(WIDE_WIDTH, HEIGHT), PROFILE.limits(), Set.of());
        ExpansionContext wide = new ExpansionContext(fixture, START, wideProfile);
        assertTrue(VoxelGeometry.canStand(wide, START));
        assertTrue(VoxelGeometry.canStand(wide, target));
        assertTrue(VoxelGeometry.clearLine(narrow, START.center(), target.center()));
        assertFalse(VoxelGeometry.clearLine(wide, START.center(), target.center()));
    }

    @Test
    public void shortBlockHeightChangesUseActualMovementLimits() {
        // 相同节点高度也可能有真实高差，不能把土径到整格误判为平走。
        Grid fixture = new Grid();
        fixture.set(START.offset(0, -1, 0), shortBlock(PATH_HEIGHT));
        GridPos target = START.offset(1, 0, 0);
        fixture.set(target.offset(0, -1, 0), Cell.SOLID);
        NavPlan plan = success(fixture, target, PROFILE);
        assertEquals(MoveKind.STEP_UP, plan.edges().get(0).kind());
        NavProfile flatOnly = profile(new MovementLimits(0, 0, 0, 0));
        assertEquals(SearchStatus.NO_PATH, search(fixture, target, flatOnly).status());
        NavResult reverse = planner.plan(fixture, request(target, START, PROFILE));
        assertEquals(MoveKind.DROP, reverse.plan().orElseThrow().edges().get(0).kind());
    }

    @Test
    public void walkStepJumpAndDropSupportAllEightDirections() {
        // 相邻两点逐一旋转和改变高度，确认每类地面能力覆盖斜向。
        for (int[] direction : DIRECTIONS) {
            for (int rise : new int[]{0, 1, JUMP_HEIGHT, -DROP_LIMIT}) {
                Grid fixture = new Grid();
                fixture.set(START.offset(0, -1, 0), Cell.SOLID);
                GridPos target = START.offset(direction[0], rise, direction[1]);
                fixture.set(target.offset(0, -1, 0), Cell.SOLID);
                NavPlan plan = success(fixture, target, PROFILE);
                assertEquals(1, plan.edges().size());
                assertTrue(plan.edges().get(0).baseCost() >= START.distanceTo(target));
            }
        }
    }

    @Test
    public void diagonalWalkIsStraightAndHasEuclideanCost() {
        // 空旷对角线应连续斜走，不能因方向缺失或代价偏低形成锯齿。
        Grid fixture = new Grid();
        fixture.floor(0, PLATFORM_END, 0, PLATFORM_END);
        GridPos target = START.offset(PLATFORM_END, 0, PLATFORM_END);
        NavPlan plan = success(fixture, target, PROFILE);
        assertEquals(PLATFORM_END, plan.edges().size());
        assertEquals(START.distanceTo(target), plan.cost(), TOLERANCE);
        for (MoveEdge edge : plan.edges()) {
            assertEquals(MoveKind.WALK, edge.kind());
            assertEquals(1, edge.to().x() - edge.from().x());
            assertEquals(1, edge.to().z() - edge.from().z());
        }
    }

    @Test
    public void diagonalMoveRejectsBlockedSidesAndWideBody() {
        // 直接检查候选边，防止其他绕路把斜向穿角问题隐藏起来。
        Grid fixture = flat();
        fixture.set(START.offset(1, 0, 0), Cell.SOLID);
        fixture.set(START.offset(0, 0, 1), Cell.SOLID);
        GridPos target = START.offset(1, 0, 1);
        MoveCapability walk = BuiltInCapabilities.all().get(0);
        ExpansionContext context = new ExpansionContext(fixture, START, PROFILE);
        assertFalse(walk.successors(context).stream().anyMatch(edge -> edge.to().equals(target)));
        Grid narrow = new Grid();
        narrow.set(START.offset(0, -1, 0), Cell.SOLID);
        narrow.set(target.offset(0, -1, 0), Cell.SOLID);
        NavProfile wide = new NavProfile(new BodySize(JUMP_HEIGHT, HEIGHT), PROFILE.limits(), Set.of());
        assertEquals(SearchStatus.INVALID_START, search(narrow, target, wide).status());
    }

    @Test
    public void lazyReadsAreCachedAndShareTheNodeBudget() {
        // 对源网格计数，重复碰撞查询只能产生一次实际单元读取。
        Grid fixture = flat();
        Map<GridPos, Integer> reads = new HashMap<>();
        VoxelGrid counting = new VoxelGrid() {
            @Override
            public GridBounds bounds() {
                return fixture.bounds();
            }

            @Override
            public Cell cellAt(GridPos position) {
                // 测试网格与真实适配遵循相同的边界外封闭约定。
                reads.merge(position, 1, Integer::sum);
                return fixture.cellAt(position);
            }
        };
        NavResult result = planner.plan(counting, request(START, START.offset(1, 0, 0), PROFILE));
        assertEquals(SearchStatus.SUCCESS, result.status());
        assertEquals(reads.size(), result.readCells());
        assertTrue(reads.values().stream().allMatch(count -> count == 1));
        assertTrue(result.workUnits() > result.visitedNodes());
        assertTrue(result.workUnits() <= NODE_BUDGET);
    }

    @Test
    public void customCapabilityCannotReadPastTheSharedBudget() {
        MoveCapability reader = new MoveCapability() {
            @Override
            public MoveKind kind() {
                return new MoveKind("test:reader");
            }

            @Override
            public List<MoveEdge> successors(ExpansionContext context) {
                // 故意大量读取，确认自定义能力也被同一个缓存和额度拦截。
                for (int x = LOWER_BOUND; x <= UPPER_BOUND; x++) {
                    for (int z = LOWER_BOUND; z <= UPPER_BOUND; z++) {
                        context.grid().cellAt(new GridPos(x, 1, z));
                    }
                }
                return List.of();
            }
        };
        int units = JUMP_HEIGHT;
        NavRequest request = new NavRequest(START, NavGoals.point(START.offset(1, 0, 0)), PROFILE, List.of(),
                new SearchBudget(units, SEARCH_RANGE));
        NavResult result = new AStarPlanner(List.of(reader)).plan(flat(), request);
        assertEquals(SearchStatus.BUDGET_EXHAUSTED, result.status());
        assertEquals(units, result.workUnits());
        assertTrue(result.readCells() <= (units - result.visitedNodes()) * SearchBudget.CELL_READS_PER_UNIT);
    }

    private static Cell shortBlock(double height) {
        return new Cell(Terrain.SOLID, Hazard.NONE, List.of(CollisionBox.floor(height)));
    }

    private static Grid landing(int gap, int rise, int z) {
        Grid fixture = new Grid();
        fixture.set(START.offset(0, -1, 0), Cell.SOLID);
        fixture.set(START.offset(gap + 1, rise - 1, z), Cell.SOLID);
        return fixture;
    }

    private NavResult search(Grid grid, GridPos target, NavProfile profile) {
        return planner.plan(grid, request(START, target, profile));
    }

    private NavPlan success(Grid grid, GridPos target, NavProfile profile) {
        NavResult result = search(grid, target, profile);
        assertEquals(SearchStatus.SUCCESS, result.status());
        return result.plan().orElseThrow();
    }

    private static NavProfile profile(MovementLimits limits) {
        return new NavProfile(new BodySize(WIDTH, HEIGHT), limits, Set.of());
    }

    private static NavRequest request(GridPos start, GridPos target, NavProfile profile) {
        return new NavRequest(start, NavGoals.point(target), profile, List.of(DangerLayer.INSTANCE),
                new SearchBudget(NODE_BUDGET, SEARCH_RANGE));
    }

    private static Grid gap() {
        Grid fixture = new Grid();
        fixture.set(START.offset(0, -1, 0), Cell.SOLID);
        fixture.set(START.offset(GAP_WIDTH + 1, -1, 0), Cell.SOLID);
        return fixture;
    }

    private static Grid flat() {
        Grid fixture = new Grid();
        fixture.floor(0, PLATFORM_END, -DETOUR_Z, DETOUR_Z);
        return fixture;
    }

    private static final class Grid implements VoxelGrid {
        private final Map<GridPos, Cell> cells = new HashMap<>();
        private final GridBounds bounds = new GridBounds(new GridPos(LOWER_BOUND, LOWER_BOUND, LOWER_BOUND),
                new GridPos(UPPER_BOUND, UPPER_BOUND, UPPER_BOUND));

        @Override
        public GridBounds bounds() {
            return bounds;
        }

        @Override
        public Cell cellAt(GridPos position) {
            // 测试边界与生产网格相同，不能在封闭范围外借到路径。
            if (!bounds.contains(position)) {
                return Cell.SOLID;
            }
            return cells.getOrDefault(position, Cell.AIR);
        }

        private void set(GridPos position, Cell cell) {
            cells.put(position, cell);
        }

        private void column(int x, int z, int topY) {
            // 完整填充到指定顶面，避免从高台内部穿过的假可达路线。
            for (int y = 0; y <= topY; y++) {
                set(new GridPos(x, y, z), Cell.SOLID);
            }
        }

        private void floor(int minX, int maxX, int minZ, int maxZ) {
            // 以包含端点的区域铺设支撑，不隐式填充区域外的可走格。
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    set(new GridPos(x, 0, z), Cell.SOLID);
                }
            }
        }
    }
}
