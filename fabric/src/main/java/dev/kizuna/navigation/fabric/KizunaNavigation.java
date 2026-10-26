package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.AStarPlanner;
import dev.kizuna.navigation.core.BodySize;
import dev.kizuna.navigation.core.CostLayer;
import dev.kizuna.navigation.core.DangerLayer;
import dev.kizuna.navigation.core.GridPos;
import dev.kizuna.navigation.core.GridBounds;
import dev.kizuna.navigation.core.VoxelGeometry;
import dev.kizuna.navigation.core.MoveKey;
import dev.kizuna.navigation.core.NavGoals;
import dev.kizuna.navigation.core.NavPlan;
import dev.kizuna.navigation.core.NavProfile;
import dev.kizuna.navigation.core.NavRequest;
import dev.kizuna.navigation.core.NavResult;
import dev.kizuna.navigation.core.SearchBudget;
import dev.kizuna.navigation.core.SearchStatus;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class KizunaNavigation extends MobNavigation implements NavHandle {
    private static final double MAX_BODY_WIDTH = 4;
    private static final double MAX_BODY_HEIGHT = 8;
    private static final double SIZE_TOLERANCE = 0.0001;
    private final ProfileProvider profiles;
    private final EntityNavigation original;
    private final Set<MoveKey> rejectedMoves = new HashSet<>();
    private MoveListener listener = MoveListener.NONE;
    private PlanFollower follower;
    private NavTicket pending;
    private NavRequest activeRequest;
    private NavState state = NavState.IDLE;
    private long retryAt;
    private int replans;

    KizunaNavigation(MobEntity mob, ProfileProvider profiles, EntityNavigation original) {
        super(mob, mob.getWorld());
        // 子类构造期间设置的门和游泳标志，在实体加入世界后完整迁移。
        this.profiles = profiles;
        this.original = original;
        nodeMaker.setCanOpenDoors(original.getNodeMaker().canOpenDoors());
        nodeMaker.setCanEnterOpenDoors(original.getNodeMaker().canEnterOpenDoors());
        setCanSwim(original.canSwim());
        speed = 1;
    }

    @Override
    public NavProfile currentProfile() {
        // 实体尺寸是碰撞体积下限，过小的宿主参数不能让规划穿过障碍。
        requireThread();
        NavProfile profile = Objects.requireNonNull(profiles.profileFor(entity), "profile");
        BodySize body = profile.body();
        double width = body.width();
        double height = body.height();
        if (width + SIZE_TOLERANCE < entity.getWidth() || height + SIZE_TOLERANCE < entity.getHeight()
                || width > MAX_BODY_WIDTH || height > MAX_BODY_HEIGHT) {
            throw new IllegalArgumentException("移动参数的体积必须包住实体，且不超过支持尺寸");
        }
        return profile;
    }

    @Override
    public NavRequest requestTo(BlockPos target) {
        requireThread();
        // 目标格内的地毯等矮碰撞可由其上一节点表达，避免把可站立顶面当作障碍。
        GridPos anchor = gridPos(target);
        GridBounds landing = new GridBounds(anchor, anchor.offset(0, 1, 0));
        return new NavRequest(position(), NavGoals.region(landing), currentProfile(), List.of(),
                KizunaNav.settings().requestBudget());
    }

    @Override
    public NavTicket navigateTo(BlockPos target, double movementSpeed) {
        setSpeed(movementSpeed);
        return planAsync(requestTo(target));
    }

    @Override
    public void setSpeed(double movementSpeed) {
        // 只接受有限正倍率，避免原版移动控制进入无效速度状态。
        requireThread();
        if (!Double.isFinite(movementSpeed) || movementSpeed <= 0) {
            throw new IllegalArgumentException("导航速度倍率必须为有限正数");
        }
        super.setSpeed(movementSpeed);
    }

    @Override
    public NavTicket planAsync(NavRequest request) {
        return planAsync(request, NavPriority.NORMAL);
    }

    @Override
    public NavTicket planAsync(NavRequest request, NavPriority priority) {
        // 新请求取代旧的排队任务；入队失败时保留正在跟随的路径状态。
        requireThread();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(priority, "priority");
        NavigationSettings settings = KizunaNav.settings();
        SearchBudget budget = request.budget();
        if (budget.maxDistance() > settings.requestBudget().maxDistance()
                || budget.maxNodes() > settings.nodesPerTick()) {
            throw new IllegalArgumentException("请求超出近距离导航预算");
        }
        cancelPending();
        rejectedMoves.clear();
        replans = 0;
        retryAt = 0;
        try {
            NavTicket ticket = enqueue(request, priority);
            state = NavState.PLANNING;
            return ticket;
        } catch (RuntimeException error) {
            state = follower == null ? NavState.IDLE : NavState.FOLLOWING;
            throw error;
        }
    }

    private NavTicket enqueue(NavRequest request, NavPriority priority) {
        NavTicket ticket = new NavTicket(this::requireThread);
        NavigationScheduler.enqueue(this, request, priority, ticket);
        pending = ticket;
        return ticket;
    }

    PlanningResult calculate(NavRequest request) {
        // 在实际计算时刷新实体位置和参数，再按需读取本次搜索用到的方块。
        NavRequest actual = prepareRequest(request);
        if (actual.start().distanceTo(actual.goal().anchor()) > actual.budget().maxDistance()) {
            return new PlanningResult(actual, NavResult.failure(SearchStatus.GOAL_OUT_OF_RANGE, 0));
        }
        AStarPlanner planner = new AStarPlanner(KizunaNav.capabilities());
        NavResult result = planner.plan(WorldSnapshots.capture((ServerWorld) world, actual, entity),
                actual, rejectedMoves);
        return new PlanningResult(actual, result);
    }

    private NavRequest prepareRequest(NavRequest request) {
        // 危险层始终最先应用，随后叠加类型与请求专属的非负代价。
        List<CostLayer> layers = new ArrayList<>();
        layers.add(DangerLayer.INSTANCE);
        layers.addAll(KizunaNav.costLayers(entity.getType()));
        layers.addAll(request.costLayers());
        return new NavRequest(position(), request.goal(), currentProfile(), layers, request.budget());
    }

    void accept(NavTicket ticket, NavRequest request, PlanningResult calculation) {
        // 只应用仍归本实体所有的请求结果，规划成功后再替换旧执行计划。
        if (pending != ticket || ticket.isCancelled() || !isAttached()) {
            return;
        }
        pending = null;
        NavResult result = calculation.result();
        if (result.status() == SearchStatus.SUCCESS) {
            clearFollower();
            NavPlan plan = result.plan().orElseThrow();
            NavProfile profile = calculation.request().profile();
            follower = new PlanFollower(entity, plan, profile, speed, KizunaNav.settings().recovery());
            currentPath = vanillaPath(plan);
            activeRequest = request;
            state = NavState.FOLLOWING;
        } else if (follower == null) {
            state = NavState.FAILED;
        } else {
            state = NavState.FOLLOWING;
        }
        ticket.complete(result);
    }

    void reject(NavTicket ticket, RuntimeException error) {
        // 失败请求不能清掉仍有效的移动段，异常经 ticket 交还调用方。
        if (pending == ticket) {
            pending = null;
            if (follower == null) {
                state = NavState.FAILED;
            } else {
                state = NavState.FOLLOWING;
            }
        }
        ticket.fail(error);
    }

    @Override
    public void tick() {
        // 沿原版导航 tick 调度执行；取消、乘坐和死亡优先于后续移动。
        tickCount++;
        if (!entity.isAlive() || entity.hasVehicle()) {
            stop();
            return;
        }
        if (pending != null && pending.isCancelled()) {
            pending = null;
            if (follower == null) {
                state = NavState.IDLE;
            } else {
                state = NavState.FOLLOWING;
            }
        }
        if (follower != null) {
            tickFollower();
        } else if (state == NavState.RECOVERING && world.getTime() >= retryAt && entity.isOnGround()) {
            retry();
        }
    }

    private void tickFollower() {
        // 段开始前重新核对参数，回调释放或替换导航后不继续操作旧执行器。
        PlanFollower active = follower;
        try {
            if (active.isStartingSegment() && !active.profile().equals(currentProfile())) {
                recover(MoveFailure.PROFILE_CHANGED);
                return;
            }
            MoveProgress progress = active.tick(listener);
            if (follower != active) {
                return;
            }
            if (progress == MoveProgress.FAILED) {
                recover(active.failure());
            } else if (progress == MoveProgress.SUCCEEDED) {
                clearFollower();
                if (pending == null) {
                    state = NavState.COMPLETED;
                }
            } else {
                currentPath.setCurrentNodeIndex(active.completedEdges() + 1);
            }
        } catch (RuntimeException error) {
            KizunaNav.LOGGER.error("移动段执行失败", error);
            recover(MoveFailure.EXECUTION_FAILED);
        }
    }

    private void recover(MoveFailure reason) {
        // 屏蔽已失败的有向段并递增退避，参数变化则允许重新使用原有路线。
        if (follower != null && reason != MoveFailure.PROFILE_CHANGED) {
            rejectedMoves.add(follower.failedEdge().key());
        }
        clearFollower();
        if (pending != null) {
            state = NavState.PLANNING;
            return;
        }
        RecoverySettings recovery = KizunaNav.settings().recovery();
        if (activeRequest == null || replans >= recovery.maxReplans()) {
            state = NavState.FAILED;
            return;
        }
        replans++;
        retryAt = world.getTime() + (long) recovery.retryDelayTicks() * replans;
        state = NavState.RECOVERING;
    }

    private void retry() {
        // 恢复请求降低队列优先级，入队失败立即结束恢复避免永久等待。
        try {
            enqueue(activeRequest, NavPriority.BACKGROUND);
            state = NavState.PLANNING;
        } catch (RuntimeException error) {
            state = NavState.FAILED;
            KizunaNav.LOGGER.warn("导航重算未能入队", error);
        }
    }

    @Override
    public void setListener(MoveListener listener) {
        requireThread();
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public Optional<NavPlan> currentPlan() {
        // 仅暴露正在跟随的不可变计划，排队中的请求不伪装成可执行路径。
        requireThread();
        if (follower == null) {
            return Optional.empty();
        }
        return Optional.of(follower.plan());
    }

    @Override
    public int replanCount() {
        requireThread();
        return replans;
    }

    @Override
    public NavState state() {
        requireThread();
        return state;
    }

    @Override
    public void stop() {
        // 同步取消规划和执行并清除恢复目标，使旧回调不能恢复已停止的导航。
        requireThread();
        cancelPending();
        clearFollower();
        activeRequest = null;
        retryAt = 0;
        state = NavState.IDLE;
    }

    private void cancelPending() {
        // 先解除句柄引用再触发取消回调，允许回调安全提交新请求。
        NavTicket previous = pending;
        pending = null;
        if (previous != null) {
            previous.cancel();
        }
    }

    private void clearFollower() {
        // 先解除执行引用，再清理控制输入，支持监听器内释放的重入。
        PlanFollower previous = follower;
        follower = null;
        currentPath = null;
        if (previous != null) {
            previous.cancel();
        }
    }

    @Override
    public Path findPathTo(BlockPos target, int distance) {
        return null;
    }

    @Override
    public Path findPathTo(Entity target, int distance) {
        return null;
    }

    @Override
    protected Path findPathToAny(Set<BlockPos> targets, int range, boolean useHeadPos,
                                 int distance, float followRange) {
        return null;
    }

    @Override
    public boolean startMovingAlong(Path path, double movementSpeed) {
        return false;
    }

    @Override
    public void recalculatePath() {
        // 原版重算请求只能触发本库恢复，不能重新启用原版寻路。
        if (follower != null) {
            recover(MoveFailure.EXECUTION_FAILED);
        }
    }

    @Override
    public BlockPos getTargetPos() {
        // 只报告实际跟随计划的终点，排队或空闲时没有活动目标。
        if (follower == null) {
            return null;
        }
        return blockPos(follower.plan().end());
    }

    EntityNavigation originalNavigation() {
        return original;
    }

    MinecraftServer server() {
        return ((ServerWorld) world).getServer();
    }

    boolean isAttached() {
        return entity.isAlive() && entity.getNavigation() == this;
    }

    private void requireThread() {
        KizunaNav.requireServerThread(entity);
    }

    private GridPos position() {
        // 使用碰撞箱脚底的向上取整值；A* 再核对脚下真实顶面和身体净空。
        int x = (int) Math.floor(entity.getX());
        int y = (int) Math.ceil(entity.getBoundingBox().minY - VoxelGeometry.EPSILON);
        int z = (int) Math.floor(entity.getZ());
        return new GridPos(x, y, z);
    }

    private static Path vanillaPath(NavPlan plan) {
        // 原版 Path 只保留观察用的整数节点，真实脚底高度由本库移动段保存。
        List<PathNode> nodes = new ArrayList<>();
        nodes.add(pathNode(plan.start()));
        plan.edges().forEach(edge -> nodes.add(pathNode(edge.to())));
        Path path = new Path(nodes, blockPos(plan.end()), true /* reachesTarget */);
        path.setCurrentNodeIndex(1);
        return path;
    }

    private static PathNode pathNode(GridPos position) {
        return new PathNode(position.x(), position.y(), position.z());
    }

    private static BlockPos blockPos(GridPos position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static GridPos gridPos(BlockPos position) {
        return new GridPos(position.getX(), position.getY(), position.getZ());
    }

    record PlanningResult(NavRequest request, NavResult result) {
    }
}
