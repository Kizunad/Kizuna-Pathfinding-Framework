package dev.kizuna.navigation.fabric.test;

import dev.kizuna.navigation.core.MoveKind;
import dev.kizuna.navigation.core.MoveEndpoints;
import dev.kizuna.navigation.core.NavResult;
import dev.kizuna.navigation.core.SearchStatus;
import dev.kizuna.navigation.fabric.KizunaNav;
import dev.kizuna.navigation.fabric.MoveContext;
import dev.kizuna.navigation.fabric.MoveDecision;
import dev.kizuna.navigation.fabric.MoveListener;
import dev.kizuna.navigation.fabric.NavHandle;
import dev.kizuna.navigation.fabric.NavState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class NavigationTerrainGameTest implements FabricGameTest {
    private static final int TICK_LIMIT = 300;
    private static final int FLOOR_Y = 2;
    private static final int FEET_Y = FLOOR_Y + 1;
    private static final int LANE_Z = 3;
    private static final int TARGET_X = 6;
    private static final int LANE_END = 7;
    private static final int SETTLE_TICKS = 2;
    private static final int JUMP_HEIGHT = 2;
    private static final int GAP_START = 2;
    private static final int GAP_END = 4;
    private static final int TRANSITION_X = 3;
    private static final int STAIR_PERIOD = 2;
    private static final int SNOW_LAYERS = 4;
    private static final int MAX_MOISTURE = 7;
    private static final int DIAGONAL_TARGET_Z = 7;
    private static final double PATH_HEIGHT = 15.0 / 16;
    private static final double SLAB_HEIGHT = 0.5;
    private static final double CARPET_HEIGHT = 1.0 / 16;
    private static final double SOUL_SAND_HEIGHT = 14.0 / 16;
    private static final double SNOW_HEIGHT = 3.0 / 8;
    private static final double ARRIVAL_DISTANCE = 0.45;
    private static final double HEIGHT_TOLERANCE = 0.03;
    private static final BlockPos START = new BlockPos(1, FEET_Y, LANE_Z);
    private static final BlockPos TARGET = new BlockPos(TARGET_X, FEET_Y, LANE_Z);

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void dirtPathRoadIsWalkable(TestContext context) {
        road(context, Blocks.DIRT_PATH.getDefaultState());
        walk(context, FLOOR_Y + PATH_HEIGHT, new Destination(TARGET, FLOOR_Y + PATH_HEIGHT));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void farmlandRoadIsWalkable(TestContext context) {
        road(context, Blocks.FARMLAND.getDefaultState().with(Properties.MOISTURE, MAX_MOISTURE));
        walk(context, FLOOR_Y + PATH_HEIGHT, new Destination(TARGET, FLOOR_Y + PATH_HEIGHT));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void carpetRoadIsWalkable(TestContext context) {
        // 把地毯放在整格支撑上，脚底位于目标格内而不是格点高度。
        road(context, Blocks.STONE.getDefaultState());
        for (int x = 0; x <= LANE_END; x++) {
            for (int z = LANE_Z - 1; z <= LANE_Z + 1; z++) {
                context.setBlockState(x, FEET_Y, z, Blocks.RED_CARPET);
            }
        }
        walk(context, FEET_Y + CARPET_HEIGHT, new Destination(TARGET, FEET_Y + CARPET_HEIGHT));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void halfSlabStaircaseUsesCollisionTops(TestContext context) {
        // 每一级只升半格，使执行器必须使用真实顶面而非整格节点高度。
        for (int x = 1; x <= TARGET_X; x++) {
            int topY = FLOOR_Y + (x - 1) / STAIR_PERIOD;
            BlockState top = Blocks.STONE.getDefaultState();
            if (x % STAIR_PERIOD != 0) {
                top = Blocks.STONE_SLAB.getDefaultState();
            }
            for (int z = LANE_Z - 1; z <= LANE_Z + 1; z++) {
                for (int y = FLOOR_Y; y < topY; y++) {
                    context.setBlockState(x, y, z, Blocks.STONE);
                }
                context.setBlockState(new BlockPos(x, topY, z), top);
            }
        }
        BlockPos target = TARGET.up(JUMP_HEIGHT);
        walk(context, FLOOR_Y + SLAB_HEIGHT, new Destination(target, FEET_Y + JUMP_HEIGHT));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void walksFromDirtPathOntoFullBlocks(TestContext context) {
        road(context, Blocks.DIRT_PATH.getDefaultState());
        replaceRoadEnd(context, Blocks.STONE.getDefaultState());
        walk(context, FLOOR_Y + PATH_HEIGHT, new Destination(TARGET, FEET_Y));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void walksFromFullBlocksDownToDirtPath(TestContext context) {
        road(context, Blocks.STONE.getDefaultState());
        replaceRoadEnd(context, Blocks.DIRT_PATH.getDefaultState());
        walk(context, FEET_Y, new Destination(TARGET, FLOOR_Y + PATH_HEIGHT));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void soulSandCollisionTopIsWalkable(TestContext context) {
        road(context, Blocks.SOUL_SAND.getDefaultState());
        walk(context, FLOOR_Y + SOUL_SAND_HEIGHT, new Destination(TARGET, FLOOR_Y + SOUL_SAND_HEIGHT));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void snowLayerCollisionTopIsWalkable(TestContext context) {
        // 雪层需要下方实体方块支撑，防止世界更新把测试路面移除。
        for (int x = 0; x <= LANE_END; x++) {
            for (int z = LANE_Z - 1; z <= LANE_Z + 1; z++) {
                context.setBlockState(x, FLOOR_Y - 1, z, Blocks.STONE);
            }
        }
        road(context, Blocks.SNOW.getDefaultState().with(Properties.LAYERS, SNOW_LAYERS));
        walk(context, FLOOR_Y + SNOW_HEIGHT, new Destination(TARGET, FLOOR_Y + SNOW_HEIGHT));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void realLeapCrossesGapAndRisesTwoBlocks(TestContext context) {
        // 空沟两岸直接相差两格，不能通过独立跳高段或中途落脚到达。
        for (int x = 0; x <= LANE_END; x++) {
            if (x >= GAP_START && x <= GAP_END) {
                continue;
            }
            int topY = FLOOR_Y;
            if (x > GAP_END) {
                topY += JUMP_HEIGHT;
            }
            for (int z = LANE_Z - 1; z <= LANE_Z + 1; z++) {
                for (int y = FLOOR_Y; y <= topY; y++) {
                    context.setBlockState(x, y, z, Blocks.STONE);
                }
            }
        }
        MobEntity mob = spawn(context, FEET_Y);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        AtomicInteger raisedLeaps = new AtomicInteger();
        handle.setListener(new MoveListener() {
            @Override
            public MoveDecision beforeMove(MoveContext move) {
                // 同时核对段类型和真实高差，独立跳高不能代替组合跨越。
                MoveEndpoints endpoints = move.edge().endpoints();
                double rise = endpoints.to().y() - endpoints.from().y();
                if (move.edge().kind().equals(MoveKind.LEAP) && rise == JUMP_HEIGHT) {
                    raisedLeaps.incrementAndGet();
                }
                return MoveDecision.ALLOW;
            }
        });
        Destination destination = new Destination(TARGET.up(JUMP_HEIGHT), FEET_Y + JUMP_HEIGHT);
        navigate(context, mob, handle, destination,
                () -> context.assertTrue(raisedLeaps.get() == 1, "必须真实执行一次跨沟加跳高"));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void realDiagonalLeapCrossesGap(TestContext context) {
        // 两岸同时错开两个水平轴，只能通过对角跨越实际到达。
        for (int x = 0; x <= 1; x++) {
            for (int z = LANE_Z - 1; z <= LANE_Z; z++) {
                context.setBlockState(x, FLOOR_Y, z, Blocks.STONE);
            }
        }
        BlockPos target = new BlockPos(GAP_END + 1, FEET_Y, DIAGONAL_TARGET_Z);
        for (int x = target.getX(); x <= TARGET_X; x++) {
            for (int z = target.getZ(); z <= target.getZ() + 1; z++) {
                context.setBlockState(x, FLOOR_Y, z, Blocks.STONE);
            }
        }
        walk(context, FEET_Y, new Destination(target, FEET_Y));
    }

    private static void road(TestContext context, BlockState state) {
        // 各种路面共用相同长度和宽度，排除路线差异对起点处理的影响。
        for (int x = 0; x <= LANE_END; x++) {
            for (int z = LANE_Z - 1; z <= LANE_Z + 1; z++) {
                context.setBlockState(new BlockPos(x, FLOOR_Y, z), state);
            }
        }
    }

    private static void replaceRoadEnd(TestContext context, BlockState state) {
        // 仅替换后半段路面，让同一条路同时覆盖不同碰撞顶面的交接。
        for (int x = TRANSITION_X; x <= LANE_END; x++) {
            for (int z = LANE_Z - 1; z <= LANE_Z + 1; z++) {
                context.setBlockState(new BlockPos(x, FLOOR_Y, z), state);
            }
        }
    }

    private static MobEntity spawn(TestContext context, double feetY) {
        // 仅在测试布置时放到碰撞顶面，移动阶段必须依赖控制器和真实物理。
        MobEntity mob = context.spawnMob(EntityType.PILLAGER, START);
        Vec3d start = Vec3d.ofBottomCenter(context.getAbsolutePos(START));
        mob.setPosition(start.add(0, feetY - FEET_Y, 0));
        return mob;
    }

    private static void walk(TestContext context, double startY, Destination destination) {
        MobEntity mob = spawn(context, startY);
        NavHandle handle = KizunaNav.takeOver(mob, NavigationGameTest::profile);
        navigate(context, mob, handle, destination);
    }

    private static void navigate(TestContext context, MobEntity mob, NavHandle handle, Destination destination) {
        navigate(context, mob, handle, destination, () -> { });
    }

    private static void navigate(TestContext context, MobEntity mob, NavHandle handle,
                                 Destination destination, Runnable atArrival) {
        // 等待物理落地后发请求，完成断言同时核对水平到点、真实高度和着地状态。
        CompletableFuture<NavResult> result = new CompletableFuture<>();
        context.runAtTick(SETTLE_TICKS, () -> handle.navigateTo(context.getAbsolutePos(destination.position()), 1)
                .result().whenComplete((plan, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(plan);
                    }
                }));
        context.addInstantFinalTask(() -> {
            context.assertTrue(result.isDone(), "规划应完成");
            NavResult plan = result.join();
            SearchStatus status = plan.status();
            context.assertTrue(status == SearchStatus.SUCCESS, "应找到真实碰撞上可走的路线，当前为 " + status);
            context.assertTrue(handle.state() == NavState.COMPLETED, "移动应完成，当前为 " + handle.state());
            Vec3d target = Vec3d.ofBottomCenter(context.getAbsolutePos(destination.position()));
            Vec3d feet = target.add(0, destination.feetY() - destination.position().getY(), 0);
            context.assertTrue(mob.getPos().distanceTo(feet) < ARRIVAL_DISTANCE, "必须实际到达指定路面");
            context.assertTrue(Math.abs(mob.getY() - feet.y) < HEIGHT_TOLERANCE, "必须落在真实顶面高度");
            context.assertTrue(mob.isOnGround(), "完成时必须实际落地");
            atArrival.run();
        });
    }

    private record Destination(BlockPos position, double feetY) {
    }
}
