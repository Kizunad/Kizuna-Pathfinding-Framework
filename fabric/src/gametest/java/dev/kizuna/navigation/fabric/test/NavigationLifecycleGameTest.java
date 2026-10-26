package dev.kizuna.navigation.fabric.test;

import dev.kizuna.navigation.core.MovementPermission;
import dev.kizuna.navigation.core.NavProfile;
import dev.kizuna.navigation.fabric.KizunaNav;
import dev.kizuna.navigation.fabric.KizunaNavigation;
import dev.kizuna.navigation.fabric.NavHandle;
import dev.kizuna.navigation.fabric.NavState;
import dev.kizuna.navigation.fabric.test.mixin.ServerWorldTestAccessor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.dimension.v1.FabricDimensions;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.entity.EntityTrackingStatus;
import net.minecraft.world.World;
import net.minecraft.world.TeleportTarget;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class NavigationLifecycleGameTest implements FabricGameTest {
    private static final int TICK_LIMIT = 300;
    private static final int FLOOR_Y = 2;
    private static final int FEET_Y = FLOOR_Y + 1;
    private static final int LANE_Z = 3;
    private static final int TARGET_X = 6;
    private static final int DOOR_X = 3;
    private static final int WALL_HEIGHT = 3;
    private static final int REMOTE_COORDINATE = 4096;
    private static final int CHUNK_SIZE = 16;
    private static final double ARRIVAL_DISTANCE = 0.7;
    private static final BlockPos START = new BlockPos(1, FEET_Y, LANE_Z);
    private static final BlockPos TARGET = new BlockPos(TARGET_X, FEET_Y, LANE_Z);

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void generationThreadConstructionWaitsUntilEntityLoad(TestContext context) {
        // 模拟结构线程完成整个村民构造，导航只能在主线程加入世界时替换。
        ServerWorld world = context.getWorld();
        CompletableFuture<VillagerEntity> construction = CompletableFuture.supplyAsync(
                () -> new VillagerEntity(EntityType.VILLAGER, world));
        context.addInstantFinalTask(() -> {
            context.assertTrue(construction.isDone(), "生成线程应完成构造");
            VillagerEntity villager = construction.join();
            context.assertFalse(villager.getNavigation() instanceof KizunaNavigation, "构造期间不能接管");
            context.assertTrue(villager.getNavigation().getNodeMaker().canOpenDoors(), "村民子类应设置开门能力");
            villager.setPosition(Vec3d.ofBottomCenter(context.getAbsolutePos(START)));
            context.assertTrue(world.spawnEntity(villager), "村民应能加入服务端世界");
            EntityNavigation managed = villager.getNavigation();
            context.assertTrue(managed instanceof KizunaNavigation, "加入世界后应接管");
            context.assertTrue(managed.getNodeMaker().canOpenDoors(), "接管必须保留子类设置的门能力");
            KizunaNav.attachRegistered(villager);
            context.assertTrue(villager.getNavigation() == managed, "重复加载检查不能二次接管");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void chunkEntityDataUnloadAndReloadRetakesRegisteredType(TestContext context) {
        // 使用真实实体区块存储完成卸载和反序列化，远离其他测试以免改变其跟踪状态。
        ServerWorld world = context.getWorld();
        ChunkPos chunk = new ChunkPos(REMOTE_COORDINATE / CHUNK_SIZE, REMOTE_COORDINATE / CHUNK_SIZE);
        world.getChunk(chunk.x, chunk.z);
        ServerEntityManager<Entity> manager = ((ServerWorldTestAccessor) world).kizunaNavigationTest$entityManager();
        manager.updateTrackingStatus(chunk, EntityTrackingStatus.TICKING);
        MobEntity mob = EntityType.VINDICATOR.create(world);
        context.assertTrue(mob != null, "应能构造已登记类型");
        mob.setPersistent();
        mob.setPosition(REMOTE_COORDINATE, world.getBottomY() + FEET_Y, REMOTE_COORDINATE);
        context.assertTrue(world.spawnEntity(mob), "实体应能加入待卸载区块");
        context.assertTrue(mob.getNavigation() instanceof KizunaNavigation, "卸载前应被托管");
        UUID id = mob.getUuid();
        manager.updateTrackingStatus(chunk, EntityTrackingStatus.HIDDEN);
        manager.flush();
        context.assertTrue(mob.isRemoved(), "实体区块应已保存并真实卸载");
        context.assertFalse(mob.getNavigation() instanceof KizunaNavigation, "卸载回调应释放托管");
        manager.updateTrackingStatus(chunk, EntityTrackingStatus.TICKING);
        context.addInstantFinalTask(() -> {
            Entity loaded = world.getEntity(id);
            context.assertTrue(loaded instanceof MobEntity, "区块实体应从存储重新加载");
            MobEntity reloaded = (MobEntity) loaded;
            context.assertTrue(reloaded != mob, "必须验证反序列化得到的新实体");
            context.assertTrue(reloaded.getNavigation() instanceof KizunaNavigation, "重新加载后应再次接管");
            EntityNavigation navigation = reloaded.getNavigation();
            KizunaNav.attachRegistered(reloaded);
            context.assertTrue(reloaded.getNavigation() == navigation, "加载补查应幂等");
            reloaded.discard();
            manager.updateTrackingStatus(chunk, EntityTrackingStatus.HIDDEN);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void villagerDoorSettingsSurviveAndOpenDoor(TestContext context) {
        // 封闭侧向绕路，同时验证子类门标志迁移与木门的真实打开。
        corridor(context);
        BlockPos door = new BlockPos(DOOR_X, FEET_Y, LANE_Z);
        BlockState lower = Blocks.OAK_DOOR.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST);
        context.setBlockState(door, lower);
        context.setBlockState(door.up(), lower.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        VillagerEntity mob = context.spawnMob(EntityType.VILLAGER, START);
        context.assertTrue(mob.getNavigation() instanceof KizunaNavigation, "村民加入世界后应接管");
        context.assertTrue(mob.getNavigation().getNodeMaker().canOpenDoors(), "构造时开门标志应被迁移");
        NavHandle handle = KizunaNav.of(mob);
        handle.navigateTo(context.getAbsolutePos(TARGET), 1);
        context.addInstantFinalTask(() -> {
            context.assertTrue(handle.state() == NavState.COMPLETED, "村民应穿过门到达终点");
            Vec3d target = Vec3d.ofBottomCenter(context.getAbsolutePos(TARGET));
            context.assertTrue(mob.getPos().distanceTo(target) < ARRIVAL_DISTANCE, "村民必须实际到点");
            context.assertTrue(context.getBlockState(door).get(Properties.OPEN), "木门应被实际打开");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void convertedEntityIsAttachedAfterConstruction(TestContext context) {
        // 由未登记实体转换到已登记类型，新对象必须通过加载事件接管。
        MobEntity original = context.spawnMob(EntityType.PILLAGER, START);
        MobEntity converted = original.convertTo(EntityType.VINDICATOR, false /* keepEquipment */);
        context.assertTrue(converted != null, "转换应产生新实体");
        context.assertTrue(converted.getNavigation() instanceof KizunaNavigation, "转换后的登记类型应自动接管");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void dimensionTransferRetakesRegisteredType(TestContext context) {
        // 绕过门户搜索仅指定落点，维度迁移仍走真实的新实体加入与旧实体卸载流程。
        MobEntity original = context.spawnMob(EntityType.VINDICATOR, START);
        ServerWorld destination = context.getWorld().getServer().getWorld(World.NETHER);
        context.assertTrue(destination != null, "测试服务端应具有下界");
        Vec3d position = new Vec3d(0, destination.getBottomY() + FEET_Y, 0);
        ChunkPos chunk = new ChunkPos(BlockPos.ofFloored(position));
        destination.getChunk(chunk.x, chunk.z);
        ServerEntityManager<Entity> manager = ((ServerWorldTestAccessor) destination)
                .kizunaNavigationTest$entityManager();
        manager.updateTrackingStatus(chunk, EntityTrackingStatus.TICKING);
        TeleportTarget target = new TeleportTarget(position, Vec3d.ZERO, 0, 0);
        MobEntity transferred = FabricDimensions.teleport(original, destination, target);
        context.assertTrue(transferred != null, "维度迁移应成功");
        context.assertTrue(transferred.getWorld() == destination, "实体应进入新世界");
        context.assertTrue(transferred.getNavigation() instanceof KizunaNavigation, "新世界加载应再次接管");
        context.assertFalse(original.getNavigation() instanceof KizunaNavigation, "旧世界卸载应释放控制权");
        transferred.discard();
        manager.updateTrackingStatus(chunk, EntityTrackingStatus.HIDDEN);
        context.complete();
    }

    public static NavProfile doorProfile(MobEntity mob) {
        NavProfile ground = NavigationGameTest.profile(mob);
        return new NavProfile(ground.body(), ground.limits(), Set.of(MovementPermission.OPEN_DOORS));
    }

    private static void corridor(TestContext context) {
        // 两侧高墙阻止绕门和翻墙，确保实际执行开门段。
        for (int x = 0; x <= TARGET_X + 1; x++) {
            for (int z = LANE_Z - 1; z <= LANE_Z + 1; z++) {
                context.setBlockState(x, FLOOR_Y, z, Blocks.STONE);
                if (z != LANE_Z) {
                    for (int y = FEET_Y; y < FEET_Y + WALL_HEIGHT; y++) {
                        context.setBlockState(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
    }
}
