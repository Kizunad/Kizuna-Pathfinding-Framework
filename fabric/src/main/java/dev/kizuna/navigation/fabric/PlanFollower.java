package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.MoveEdge;
import dev.kizuna.navigation.core.NavPlan;
import dev.kizuna.navigation.core.NavProfile;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

final class PlanFollower {
    private static final int MAX_SEGMENT_TICKS = 200;
    private final MobEntity mob;
    private final NavPlan plan;
    private final NavProfile profile;
    private final double speed;
    private final RecoverySettings recovery;
    private int completedEdges;
    private int stagnantTicks;
    private int segmentTicks;
    private Vec3d lastProgress;
    private MoveContext current;
    private MoveExecutor executor;
    private boolean cancelled;
    private MoveFailure failure = MoveFailure.EXECUTION_FAILED;

    PlanFollower(MobEntity mob, NavPlan plan, NavProfile profile, double speed, RecoverySettings recovery) {
        // 每个执行计划固定参数并从实体当前位置开始累计进展。
        this.mob = mob;
        this.plan = plan;
        this.profile = profile;
        this.speed = speed;
        this.recovery = recovery;
        lastProgress = mob.getPos();
    }

    MoveProgress tick(MoveListener listener) {
        // 只有实际执行成功才推进段索引，回调取消后不能继续使用旧执行器。
        if (completedEdges == plan.edges().size()) {
            return MoveProgress.SUCCEEDED;
        }
        if (current == null && !begin(listener)) {
            return MoveProgress.FAILED;
        }
        MoveProgress progress = executor.tick();
        if (progress == MoveProgress.SUCCEEDED) {
            listener.completed(current);
            if (cancelled) {
                return MoveProgress.FAILED;
            }
            executor.cancel();
            current = null;
            executor = null;
            completedEdges++;
            return MoveProgress.RUNNING;
        }
        if (progress == MoveProgress.FAILED) {
            return fail(listener, MoveFailure.EXECUTION_FAILED);
        }
        if (stuck()) {
            return fail(listener, MoveFailure.STUCK);
        }
        return MoveProgress.RUNNING;
    }

    private boolean begin(MoveListener listener) {
        // 先让宿主批准移动，再创建执行器和重置卡住统计。
        MoveEdge edge = plan.edges().get(completedEdges);
        current = new MoveContext(mob, edge, profile, speed);
        MoveDecision decision = Objects.requireNonNull(listener.beforeMove(current), "moveDecision");
        if (cancelled) {
            return false;
        }
        if (decision == MoveDecision.DENY) {
            fail(listener, MoveFailure.VETOED);
            return false;
        }
        executor = KizunaNav.executor(edge.kind()).create(current);
        segmentTicks = 0;
        stagnantTicks = 0;
        lastProgress = mob.getPos();
        return true;
    }

    private boolean stuck() {
        // 用累计位移而非单 tick 抖动判定进展，同时限制单段总持续时间。
        segmentTicks++;
        double threshold = recovery.minProgress();
        Vec3d position = mob.getPos();
        if (position.squaredDistanceTo(lastProgress) >= threshold * threshold) {
            lastProgress = position;
            stagnantTicks = 0;
        } else {
            stagnantTicks++;
        }
        return stagnantTicks >= recovery.stuckTicks() || segmentTicks >= MAX_SEGMENT_TICKS;
    }

    private MoveProgress fail(MoveListener listener, MoveFailure reason) {
        failure = reason;
        listener.failed(current, reason);
        return MoveProgress.FAILED;
    }

    void cancel() {
        // 取消具备幂等性，监听器内释放和外部停止共用同一清理入口。
        if (cancelled) {
            return;
        }
        cancelled = true;
        MoveExecutor previous = executor;
        executor = null;
        if (previous != null) {
            previous.cancel();
        }
        BuiltInExecutors.clearInput(mob);
    }

    boolean isStartingSegment() {
        return current == null;
    }

    NavProfile profile() {
        return profile;
    }

    NavPlan plan() {
        return plan;
    }

    int completedEdges() {
        return completedEdges;
    }

    MoveEdge failedEdge() {
        return plan.edges().get(completedEdges);
    }

    MoveFailure failure() {
        return failure;
    }
}
