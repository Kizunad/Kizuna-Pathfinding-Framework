package dev.kizuna.navigation.fabric.test;

import dev.kizuna.navigation.core.BodySize;
import dev.kizuna.navigation.core.GridBounds;
import dev.kizuna.navigation.core.GridPos;
import dev.kizuna.navigation.core.MoveKind;
import dev.kizuna.navigation.core.MovementLimits;
import dev.kizuna.navigation.core.MovementPermission;
import dev.kizuna.navigation.core.NavProfile;
import dev.kizuna.navigation.core.NavGoal;
import dev.kizuna.navigation.core.NavGoals;
import dev.kizuna.navigation.core.NavRequest;
import dev.kizuna.navigation.core.NavResult;
import dev.kizuna.navigation.core.SearchStatus;
import dev.kizuna.navigation.fabric.KizunaNav;
import dev.kizuna.navigation.fabric.KizunaNavigation;
import dev.kizuna.navigation.fabric.MoveContext;
import dev.kizuna.navigation.fabric.MoveDecision;
import dev.kizuna.navigation.fabric.MoveFailure;
import dev.kizuna.navigation.fabric.MoveListener;
import dev.kizuna.navigation.fabric.NavHandle;
import dev.kizuna.navigation.fabric.NavState;
import dev.kizuna.navigation.fabric.NavTicket;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.function.Function;

public final class NavigationGameTest implements FabricGameTest {
    private static final int TICK_LIMIT = 300;
    private static final int FLOOR_Y = 2;
    private static final int FEET_Y = FLOOR_Y + 1;
    private static final int LANE_Z = 3;
    private static final int MIN_LANE_Z = 2;
    private static final int MAX_LANE_Z = 4;
    private static final int GAP_FIRST_X = 2;
    private static final int GAP_LAST_X = 4;
    private static final int TARGET_X = 6;
    private static final int FLAT_END = 7;
    private static final int JUMP_HEIGHT = 2;
    private static final int GAP_WIDTH = 3;
    private static final int SETTLE_TICKS = 2;
    private static final double ARRIVAL_DISTANCE = 0.7;
    private static final int EAGER_READ_BASELINE = 188_538;
    private static final int MIN_READ_REDUCTION_FACTOR = 10;
    private static final int DISTANT_ANCHOR = 100;
    private static final int SAFE_RADIUS = 10;
    private static final String QUEUE_FULL_MESSAGE = "导航请求队列已满";
    private static final BlockPos START = new BlockPos(1, FEET_Y, LANE_Z);
    private static final BlockPos TARGET = new BlockPos(TARGET_X, FEET_Y, LANE_Z);

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void reachedRadiusWithDistantCenterReturnsEmptyPlan(TestContext context) {
        // 目标半径覆盖起点时，Fabric 接线也应接受空路径。
        assertGoalPlan(context, start -> NavGoals.within(
                start.offset(DISTANT_ANCHOR, 0, 0), DISTANT_ANCHOR), 0);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void reachedRegionWithDistantCornerReturnsEmptyPlan(TestContext context) {
        // 区域的最小角在范围外，不影响位于区域内的起点。
        assertGoalPlan(context, start -> NavGoals.region(
                new GridBounds(start.offset(-DISTANT_ANCHOR, 0, 0), start)), 0);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void reachedAwayGoalWithDistantThreatReturnsEmptyPlan(TestContext context) {
        // 已远离威胁的实体无需向威胁表示点寻路。
        assertGoalPlan(context, start -> NavGoals.awayFrom(
                start.offset(DISTANT_ANCHOR, 0, 0), SAFE_RADIUS), 0);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void nearbyGoalBoundaryWithDistantCenterIsReachable(TestContext context) {
        // 起点不满足目标，但最近满足点在搜索半径内。
        assertGoalPlan(context, start -> NavGoals.within(
                start.offset(DISTANT_ANCHOR, 0, 0), DISTANT_ANCHOR - 1), 1);
    }

    private static void assertGoalPlan(TestContext context, Function<GridPos, NavGoal> goals, int endOffset) {
        // 从实体实际脚底构造目标，经公开异步入口验证规划终点和空路径语义。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        NavRequest base = handle.requestTo(context.getAbsolutePos(TARGET));
        GridPos start = base.start();
        NavGoal goal = goals.apply(start);
        context.assertTrue(goal.reached(start) == (endOffset == 0), "夹具的起点达成状态错误");
        NavRequest request = new NavRequest(start, goal, base.profile(), List.of(), base.budget());
        CompletableFuture<NavResult> result = handle.planAsync(request).result().toCompletableFuture();
        context.addInstantFinalTask(() -> {
            context.assertTrue(result.isDone(), "规划尚未完成");
            NavResult planned = result.join();
            context.assertTrue(planned.status() == SearchStatus.SUCCESS, "目标集合应可达");
            var plan = planned.plan().orElseThrow();
            context.assertTrue(plan.end().equals(start.offset(endOffset, 0, 0)), "规划终点错误");
            context.assertTrue(plan.edges().isEmpty() == (endOffset == 0), "空路径语义错误");
            KizunaNav.release(mob);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void cancellationCallbackKeepsEveryAcceptedTicketTerminal(TestContext context) {
        // 取消回调同步重入时，后提交者成功，被替换的两张票据都必须取消。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        BlockPos target = context.getAbsolutePos(TARGET);
        AtomicReference<NavTicket> reentrant = new AtomicReference<>();
        NavTicket first = handle.navigateTo(target, 1);
        CompletableFuture<NavResult> firstResult = first.result().toCompletableFuture();
        firstResult.whenComplete((result, failure) -> reentrant.set(handle.navigateTo(target, 1)));
        NavTicket outer = handle.navigateTo(target, 1);
        NavTicket inner = reentrant.get();
        context.assertTrue(first.isCancelled(), "首票据必须同步取消");
        context.assertTrue(inner != null, "取消回调必须已提交重入请求");
        context.assertTrue(outer.isCancelled(), "外层票据应被后提交的重入请求取消");
        CompletableFuture<NavResult> innerResult = inner.result().toCompletableFuture();
        context.addInstantFinalTask(() -> {
            context.assertTrue(innerResult.isDone(), "重入票据必须结束");
            context.assertTrue(innerResult.join().status() == SearchStatus.SUCCESS, "重入请求必须规划成功");
            context.assertTrue(firstResult.isDone(), "首票据不能悬挂");
            context.assertTrue(outer.result().toCompletableFuture().isDone(), "外层票据不能悬挂");
            KizunaNav.release(mob);
            context.assertTrue(innerResult.isDone(), "释放后重入票据仍应有终态");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void fullQueueAllowsReplacingOwnPendingRequest(TestContext context) {
        // 在同一 tick 填满队列，自身替换必须释放旧排队项的容量并最终规划成功。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        BlockPos target = context.getAbsolutePos(TARGET);
        NavTicket first = handle.navigateTo(target, 1);
        List<MobEntity> fillers = new ArrayList<>();
        NavTicket replacement;
        try {
            fillRequestQueue(context, target, fillers);
            replacement = handle.navigateTo(target, 1);
            context.assertTrue(first.isCancelled(), "自身替换必须同步取消旧票据");
            context.assertFalse(replacement.isCancelled(), "满队列不能取消已接纳的新票据");
        } finally {
            removeQueueFillers(fillers);
        }
        CompletableFuture<NavResult> result = replacement.result().toCompletableFuture();
        context.addInstantFinalTask(() -> {
            context.assertTrue(result.isDone(), "替换票据必须结束");
            context.assertTrue(result.join().status() == SearchStatus.SUCCESS, "自身替换应规划成功");
            KizunaNav.release(mob);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void fullQueueRejectsAnotherEntity(TestContext context) {
        // 其他实体不能占用已满队列，也不能取消原实体已接纳的请求。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        BlockPos target = context.getAbsolutePos(TARGET);
        NavTicket first = handle.navigateTo(target, 1);
        var other = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle otherHandle = KizunaNav.takeOver(other, NavigationGameTest::profile);
        List<MobEntity> fillers = new ArrayList<>();
        try {
            fillRequestQueue(context, target, fillers);
            assertQueueFull(context, otherHandle, target);
            context.assertFalse(first.isCancelled(), "其他实体被拒绝不能取消原票据");
            context.assertTrue(handle.state() == NavState.PLANNING, "原请求必须仍在排队");
            context.assertTrue(otherHandle.state() == NavState.IDLE, "拒绝后不能发布新请求");
        } finally {
            removeQueueFillers(fillers);
            KizunaNav.release(other);
            other.discard();
        }
        CompletableFuture<NavResult> result = first.result().toCompletableFuture();
        context.addInstantFinalTask(() -> {
            context.assertTrue(result.isDone(), "原票据必须结束");
            context.assertTrue(result.join().status() == SearchStatus.SUCCESS, "原请求应保持有效");
            KizunaNav.release(mob);
        });
    }

    private static void fillRequestQueue(TestContext context, BlockPos target, List<MobEntity> fillers) {
        // 按配置上限有界填充共享队列，允许其他测试已有排队项，退出前确认容量已满。
        int queueLimit = KizunaNav.settings().maxQueuedRequests();
        for (int index = 0; index < queueLimit; index++) {
            var mob = context.spawnMob(EntityType.PILLAGER, START);
            fillers.add(mob);
            NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
            try {
                handle.navigateTo(target, 1);
            } catch (IllegalStateException error) {
                context.assertTrue(QUEUE_FULL_MESSAGE.equals(error.getMessage()), "填充应仅因队列已满结束");
                return;
            }
        }
        throw new IllegalStateException("夹具应在原请求之外填满队列");
    }

    private static void assertQueueFull(TestContext context, NavHandle handle, BlockPos target) {
        // 只接受明确的队列容量拒绝，不能把其他提交错误当作通过。
        try {
            handle.navigateTo(target, 1);
        } catch (IllegalStateException error) {
            context.assertTrue(QUEUE_FULL_MESSAGE.equals(error.getMessage()), "应因队列已满拒绝其他实体");
            return;
        }
        throw new IllegalStateException("满队列不应接纳其他实体的新请求");
    }

    private static void removeQueueFillers(List<MobEntity> fillers) {
        // 同步清除填充请求与实体，避免将队列压力和实体碰撞带入后续 tick。
        for (MobEntity mob : fillers) {
            KizunaNav.release(mob);
            mob.discard();
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void stopCancellationCallbackCanSubmitNewRequest(TestContext context) {
        // 停止已清除旧目标，回调提交的新请求必须保留规划状态并最终完成。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        BlockPos target = context.getAbsolutePos(TARGET);
        AtomicReference<NavTicket> reentrant = new AtomicReference<>();
        NavTicket first = handle.navigateTo(target, 1);
        first.result().whenComplete((result, failure) -> reentrant.set(handle.navigateTo(target, 1)));
        handle.stop();
        context.assertTrue(first.isCancelled(), "停止必须取消旧票据");
        context.assertTrue(handle.state() == NavState.PLANNING, "新请求必须保留规划状态");
        CompletableFuture<NavResult> result = reentrant.get().result().toCompletableFuture();
        context.addInstantFinalTask(() -> {
            context.assertTrue(result.isDone(), "停止回调中的新票据必须结束");
            context.assertTrue(result.join().status() == SearchStatus.SUCCESS, "新请求应成功");
            KizunaNav.release(mob);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void releaseRejectsSubmissionFromCancellationCallback(TestContext context) {
        // 释放先解除托管，取消回调不能重新接纳已失去实体控制权的任务。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        BlockPos target = context.getAbsolutePos(TARGET);
        AtomicReference<RuntimeException> rejected = new AtomicReference<>();
        NavTicket first = handle.navigateTo(target, 1);
        first.result().whenComplete((result, failure) -> {
            try {
                handle.navigateTo(target, 1);
            } catch (RuntimeException error) {
                rejected.set(error);
            }
        });
        KizunaNav.release(mob);
        context.assertTrue(first.isCancelled(), "释放必须取消票据");
        context.assertTrue(rejected.get() instanceof IllegalStateException, "释放后的重入提交必须明确拒绝");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void runtimeManagedPillagerCrossesThreeBlockGap(TestContext context) {
        // 以真实跨沟到点验收单体接管，并记录同场景的首次读取量。
        gap(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        EntityNavigation original = mob.getNavigation();
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        context.assertTrue(mob.getNavigation() instanceof KizunaNavigation, "单体接管必须替换导航字段");
        AtomicInteger leaps = countLeaps(handle);
        CompletableFuture<NavResult> result = handle.navigateTo(context.getAbsolutePos(TARGET), 1)
                .result().toCompletableFuture();
        context.assertFalse(result.isDone(), "请求必须通过 tick 队列延后完成");
        result.thenAccept(plan -> LoggerFactory.getLogger("kizuna_navigation_test").info(
                "三格沟读取对比：before={}，after={}，visited={}，work={}",
                EAGER_READ_BASELINE, plan.readCells(), plan.visitedNodes(), plan.workUnits()));
        context.addInstantFinalTask(() -> {
            assertArrived(context, mob, handle, result);
            context.assertTrue(leaps.get() == 1, "应执行一次三格跨越");
            context.assertTrue(result.join().readCells() < EAGER_READ_BASELINE / MIN_READ_REDUCTION_FACTOR,
                    "读取量应显著小于全量快照");
            KizunaNav.release(mob);
            context.assertTrue(mob.getNavigation() == original, "释放必须还原同一个原版导航实例");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void registeredVindicatorCrossesGap(TestContext context) {
        // 已登记类型必须经世界加载入口自动接管并实际执行请求。
        gap(context);
        var mob = context.spawnMob(EntityType.VINDICATOR, START);
        context.assertTrue(mob.getNavigation() instanceof KizunaNavigation, "整类登记应在加入世界后接管");
        NavHandle handle = KizunaNav.of(mob);
        CompletableFuture<NavResult> result = handle.navigateTo(context.getAbsolutePos(TARGET), 1)
                .result().toCompletableFuture();
        context.addInstantFinalTask(() -> assertArrived(context, mob, handle, result));
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void managedVanillaPathOverloadsReturnNull(TestContext context) {
        // 逐一调用原版寻路入口，确保托管实体无法取得第二套移动计划。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        KizunaNav.takeOver(mob, NavigationGameTest::profile);
        EntityNavigation navigation = mob.getNavigation();
        BlockPos target = context.getAbsolutePos(TARGET);
        var other = context.spawnMob(EntityType.COW, TARGET);
        context.assertTrue(navigation.findPathTo(target, 0) == null, "BlockPos 入口须返回空");
        context.assertTrue(navigation.findPathTo(target, 0, TARGET_X) == null, "范围入口须返回空");
        context.assertTrue(navigation.findPathTo(target.getX(), target.getY(), target.getZ(), 0) == null,
                "final 坐标入口须返回空");
        context.assertTrue(navigation.findPathTo(other, 0) == null, "实体入口须返回空");
        context.assertTrue(navigation.findPathTo(Set.of(target), 0) == null, "集合入口须返回空");
        context.assertTrue(navigation.findPathToAny(Stream.of(target), 0) == null, "流入口须返回空");
        context.assertFalse(navigation.startMovingTo(target.getX(), target.getY(), target.getZ(), 1),
                "原版移动请求不能取得托管导航控制权");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void unregisteredCowMatchesVanillaPathAndMovement(TestContext context) {
        // 未登记生物的路径与新的原版导航逐节点对照，并验证真实移动。
        flat(context);
        var cow = context.spawnMob(EntityType.COW, START);
        EntityNavigation original = cow.getNavigation();
        context.assertTrue(original.getClass() == MobNavigation.class, "未登记生物须保持原版导航类");
        context.runAtTick(SETTLE_TICKS, () -> {
            BlockPos target = context.getAbsolutePos(TARGET);
            Path actual = original.findPathTo(target, 0);
            Path expected = new MobNavigation(cow, context.getWorld()).findPathTo(target, 0);
            assertSamePath(context, actual, expected);
            context.assertTrue(original.startMovingAlong(actual, 1), "原版寻路结果应能正常跟随");
        });
        context.addInstantFinalTask(() -> {
            context.assertTrue(cow.getNavigation() == original, "未登记生物导航实例不应变化");
            assertPosition(context, cow, TARGET);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void vetoedLeapReplansAlongWalkableBridge(TestContext context) {
        // 先否决最短跨越段，再验证恢复能选择需要绕行的桥面。
        gap(context);
        for (int x = GAP_FIRST_X; x <= GAP_LAST_X; x++) {
            context.setBlockState(x, FLOOR_Y, MIN_LANE_Z, Blocks.STONE);
        }
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        AtomicInteger vetoes = new AtomicInteger();
        handle.setListener(new MoveListener() {
            @Override
            public MoveDecision beforeMove(MoveContext move) {
                if (move.edge().kind().equals(MoveKind.LEAP)) {
                    // 只否决跨越，让恢复仍可选择绕行桥面。
                    vetoes.incrementAndGet();
                    return MoveDecision.DENY;
                }
                return MoveDecision.ALLOW;
            }
        });
        CompletableFuture<NavResult> result = handle.navigateTo(context.getAbsolutePos(TARGET), 1)
                .result().toCompletableFuture();
        context.addInstantFinalTask(() -> {
            assertArrived(context, mob, handle, result);
            context.assertTrue(vetoes.get() >= 1, "监听器必须能否决跨越");
            context.assertTrue(handle.replanCount() >= 1, "否决后必须重算普通路线");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void stalledCustomMoveRecoversWithBackoff(TestContext context) {
        // 用不产生位移的执行器触发真实卡住检测和一次退避重算。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::stallProfile);
        AtomicInteger stuckEvents = new AtomicInteger();
        handle.setListener(new MoveListener() {
            @Override
            public void failed(MoveContext move, MoveFailure reason) {
                // 只统计真实卡住事件，不把取消或普通重算计入结果。
                if (reason == MoveFailure.STUCK) {
                    stuckEvents.incrementAndGet();
                }
            }
        });
        BlockPos target = START.east(JUMP_HEIGHT);
        CompletableFuture<NavResult> result = handle.navigateTo(context.getAbsolutePos(target), 1)
                .result().toCompletableFuture();
        context.addInstantFinalTask(() -> {
            context.assertTrue(handle.state() == NavState.COMPLETED, "卡住后应沿普通路径完成");
            assertPosition(context, mob, target);
            context.assertTrue(result.join().status() == SearchStatus.SUCCESS, "初次规划应成功");
            context.assertTrue(stuckEvents.get() == 1, "卡住段应报告一次失败");
            context.assertTrue(handle.replanCount() == 1, "应只进行一次带退避的重算");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void replacedRequestsCancelAndReleaseRestoresNavigation(TestContext context) {
        // 排队请求替换和接管释放都必须同步取消旧票据。
        flat(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        EntityNavigation original = mob.getNavigation();
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        NavTicket first = handle.navigateTo(context.getAbsolutePos(TARGET), 1);
        NavTicket second = handle.navigateTo(context.getAbsolutePos(START.east()), 1);
        context.assertTrue(first.isCancelled(), "新请求应取消尚未执行的旧请求");
        KizunaNav.release(mob);
        context.assertTrue(second.isCancelled(), "释放应取消排队请求");
        context.assertTrue(mob.getNavigation() == original, "释放应精确还原原导航");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void twoBlockJumpUsesRealCollisionAndLanding(TestContext context) {
        // 目标只高两格，实体必须使用实际速度起跳并落在高台顶面。
        flat(context);
        BlockPos target = START.east().up(JUMP_HEIGHT);
        for (int rise = 1; rise <= JUMP_HEIGHT; rise++) {
            context.setBlockState(START.east().down().up(rise), Blocks.STONE);
        }
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        AtomicInteger jumps = new AtomicInteger();
        handle.setListener(new MoveListener() {
            @Override
            public MoveDecision beforeMove(MoveContext move) {
                if (move.edge().kind().equals(MoveKind.JUMP_UP)) {
                    // 段启动后才计数，规划中的候选跳跃不代表真实执行。
                    jumps.incrementAndGet();
                }
                return MoveDecision.ALLOW;
            }
        });
        handle.navigateTo(context.getAbsolutePos(target), 1);
        context.addInstantFinalTask(() -> {
            context.assertTrue(handle.state() == NavState.COMPLETED, "两格跳高必须实际落地完成");
            assertPosition(context, mob, target);
            context.assertTrue(jumps.get() == 1, "应执行一次两格跳高");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void listenerMayReleaseBeforeJumpWithoutLaunching(TestContext context) {
        // 在段开始监听器中释放控制权，后续不得继续启动旧跳跃。
        gap(context);
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        EntityNavigation original = mob.getNavigation();
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        AtomicLong releasedAt = new AtomicLong(-1);
        handle.setListener(new MoveListener() {
            @Override
            public MoveDecision beforeMove(MoveContext move) {
                KizunaNav.release(move.mob());
                releasedAt.set(context.getTick());
                return MoveDecision.ALLOW;
            }
        });
        handle.navigateTo(context.getAbsolutePos(TARGET), 1);
        context.addInstantFinalTask(() -> {
            long releaseTick = releasedAt.get();
            context.assertTrue(releaseTick >= 0, "监听器应已释放导航");
            context.assertTrue(context.getTick() >= releaseTick + SETTLE_TICKS, "释放后应继续观察实体");
            context.assertTrue(mob.getNavigation() == original, "回调内释放应保留原版导航");
            context.assertTrue(handle.state() == NavState.IDLE, "已释放的句柄须保持空闲");
            assertPosition(context, mob, START);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void changedProfileReplansBeforeUsingOldJump(TestContext context) {
        // 规划完成后撤销跨越能力，段开始前必须改走桥面。
        gap(context);
        for (int x = GAP_FIRST_X; x <= GAP_LAST_X; x++) {
            context.setBlockState(x, FLOOR_Y, MIN_LANE_Z, Blocks.STONE);
        }
        var mob = context.spawnMob(EntityType.PILLAGER, START);
        NavProfile initial = profile(mob);
        AtomicReference<NavProfile> profiles = new AtomicReference<>(initial);
        NavHandle handle = KizunaNav.takeOver(mob, ignored -> profiles.get());
        AtomicInteger leaps = countLeaps(handle);
        NavProfile walking = new NavProfile(initial.body(), new MovementLimits(1, 1, 1, 0), Set.of());
        CompletableFuture<NavResult> result = handle.navigateTo(context.getAbsolutePos(TARGET), 1)
                .result().toCompletableFuture();
        result.thenAccept(ignored -> profiles.set(walking));
        context.addInstantFinalTask(() -> {
            assertArrived(context, mob, handle, result);
            context.assertTrue(leaps.get() == 0, "参数改变后不能执行旧的跨越段");
            context.assertTrue(handle.replanCount() == 1, "参数改变应触发一次重算");
        });
    }

    public static NavProfile profile(MobEntity mob) {
        BodySize body = new BodySize(mob.getWidth(), mob.getHeight());
        return new NavProfile(body, new MovementLimits(1, JUMP_HEIGHT, 1, GAP_WIDTH), Set.of());
    }

    private static NavProfile stallProfile(MobEntity mob) {
        BodySize body = new BodySize(mob.getWidth(), mob.getHeight());
        return new NavProfile(body, new MovementLimits(1, 1, 1, 0), Set.of(MovementPermission.SWIM));
    }

    private static AtomicInteger countLeaps(NavHandle handle) {
        // 只在段被实际批准开始时计数，避免把规划候选误算成执行。
        AtomicInteger leaps = new AtomicInteger();
        handle.setListener(new MoveListener() {
            @Override
            public MoveDecision beforeMove(MoveContext move) {
                if (move.edge().kind().equals(MoveKind.LEAP)) {
                    // 统计得到批准的执行段，不能将搜索候选当成完成跨越。
                    leaps.incrementAndGet();
                }
                return MoveDecision.ALLOW;
            }
        });
        return leaps;
    }

    private static void assertArrived(TestContext context, MobEntity mob, NavHandle handle,
                                      CompletableFuture<NavResult> result) {
        context.assertTrue(result.isDone(), "规划应完成");
        context.assertTrue(result.join().status() == SearchStatus.SUCCESS, "规划应找到可达路径");
        context.assertTrue(handle.state() == NavState.COMPLETED, "移动段应全部完成，当前为 " + handle.state());
        assertPosition(context, mob, TARGET);
    }

    private static void assertPosition(TestContext context, MobEntity mob, BlockPos relativeTarget) {
        Vec3d target = Vec3d.ofBottomCenter(context.getAbsolutePos(relativeTarget));
        context.assertTrue(mob.getPos().distanceTo(target) < ARRIVAL_DISTANCE,
                "实体必须实际到达目标，当前位置 " + context.getRelative(mob.getPos()));
    }

    private static void assertSamePath(TestContext context, Path actual, Path expected) {
        // 逐节点对照原版路径，路径长度相同不足以证明行为一致。
        context.assertTrue(actual != null && expected != null, "原版应找到路径");
        context.assertTrue(actual.getLength() == expected.getLength(), "路径长度须与原版一致");
        for (int index = 0; index < actual.getLength(); index++) {
            context.assertTrue(actual.getNodePos(index).equals(expected.getNodePos(index)), "路径节点须与原版一致");
        }
    }

    private static void flat(TestContext context) {
        // 铺设统一高度的地面，隔离实体导航接管对原版移动的影响。
        for (int x = 0; x <= FLAT_END; x++) {
            for (int z = 1; z <= FLAT_END; z++) {
                context.setBlockState(x, FLOOR_Y, z, Blocks.STONE);
            }
        }
    }

    private static void gap(TestContext context) {
        // 只铺两岸不填空沟，保证测试必须实际离地跨越。
        for (int x = 0; x <= FLAT_END; x++) {
            if (x >= GAP_FIRST_X && x <= GAP_LAST_X) {
                continue;
            }
            for (int z = MIN_LANE_Z; z <= MAX_LANE_Z; z++) {
                context.setBlockState(x, FLOOR_Y, z, Blocks.STONE);
            }
        }
    }
}
