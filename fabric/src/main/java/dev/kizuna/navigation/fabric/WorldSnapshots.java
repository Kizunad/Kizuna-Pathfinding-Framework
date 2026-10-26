package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.Cell;
import dev.kizuna.navigation.core.CollisionBox;
import dev.kizuna.navigation.core.GridBounds;
import dev.kizuna.navigation.core.GridPos;
import dev.kizuna.navigation.core.Hazard;
import dev.kizuna.navigation.core.NavRequest;
import dev.kizuna.navigation.core.BodySize;
import dev.kizuna.navigation.core.Terrain;
import dev.kizuna.navigation.core.VoxelGrid;
import dev.kizuna.navigation.core.MotionPoint;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

import java.util.List;

final class WorldSnapshots {
    private static final int CHUNK_COORDINATE_SHIFT = 4;
    private WorldSnapshots() {
    }

    static VoxelGrid capture(ServerWorld world, NavRequest request, MobEntity mob) {
        // 只确定封闭边界；A* 首次访问某格时才在主线程读取实际碰撞形状。
        KizunaNav.requireServerThread(mob);
        GridPos start = request.start();
        int startY = start.y();
        int radius = request.budget().maxDistance();
        BodySize body = request.profile().body();
        int bodyMargin = (int) Math.ceil(body.height());
        int horizontalMargin = (int) Math.ceil(body.width());
        int horizontalRadius = radius + horizontalMargin;
        int minY = Math.max(world.getBottomY(), startY - radius - 1);
        int maxY = Math.min(world.getTopY() - 1, startY + radius + bodyMargin);
        GridBounds bounds = new GridBounds(start.offset(-horizontalRadius, minY - startY, -horizontalRadius),
                start.offset(horizontalRadius, maxY - startY, horizontalRadius));
        ShapeContext shapes = ShapeContext.of(mob);
        return new VoxelGrid() {
            @Override
            public GridBounds bounds() {
                return bounds;
            }

            @Override
            public Cell cellAt(GridPos position) {
                // 惰性访问时再次检查线程，不能把带世界引用的网格交给后台读取。
                KizunaNav.requireServerThread(mob);
                if (!bounds.contains(position)) {
                    return Cell.SOLID;
                }
                return read(world, position, shapes);
            }
        };
    }

    private static Cell read(ServerWorld world, GridPos position, ShapeContext shapes) {
        // 未加载区块封闭处理，导航读取不能主动生成或加载世界。
        int x = position.x();
        int y = position.y();
        int z = position.z();
        BlockPos block = new BlockPos(x, y, z);
        int chunkX = x >> CHUNK_COORDINATE_SHIFT;
        int chunkZ = z >> CHUNK_COORDINATE_SHIFT;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return Cell.SOLID;
        }
        BlockState state = world.getBlockState(block);
        List<CollisionBox> collisions = state.getCollisionShape(world, block, shapes).getBoundingBoxes().stream()
                .map(box -> new CollisionBox(new MotionPoint(box.minX, box.minY, box.minZ),
                        new MotionPoint(box.maxX, box.maxY, box.maxZ)))
                .toList();
        Terrain terrain = terrain(state, collisions);
        Hazard hazard = hazard(state);
        if (terrain == Terrain.AIR && hazard == Hazard.NONE) {
            return Cell.AIR;
        }
        if (terrain == Terrain.SOLID && hazard == Hazard.NONE && collisions.equals(Cell.SOLID.collisions())) {
            return Cell.SOLID;
        }
        return new Cell(terrain, hazard, collisions);
    }

    private static Terrain terrain(BlockState state, List<CollisionBox> collisions) {
        // 门、水与可攀附介质单独分类，普通方块的精确形状由碰撞箱保存。
        if (state.getBlock() instanceof DoorBlock) {
            if (state.get(Properties.OPEN)) {
                return Terrain.OPEN_DOOR;
            }
            if (state.isIn(BlockTags.WOODEN_DOORS)) {
                return Terrain.WOOD_DOOR;
            }
            return Terrain.IRON_DOOR;
        }
        if (state.getFluidState().isIn(FluidTags.WATER)) {
            return Terrain.WATER;
        }
        if (state.isIn(BlockTags.CLIMBABLE)) {
            return Terrain.CLIMBABLE;
        }
        if (!collisions.isEmpty()) {
            return Terrain.SOLID;
        }
        return Terrain.AIR;
    }

    private static Hazard hazard(BlockState state) {
        // 独立记录危险属性，非实心的火焰、岩浆等同样参与默认禁止层。
        if (state.getFluidState().isIn(FluidTags.LAVA)) {
            return Hazard.LAVA;
        }
        if (state.isIn(BlockTags.FIRE)) {
            return Hazard.FIRE;
        }
        if (state.isOf(Blocks.CACTUS)) {
            return Hazard.CACTUS;
        }
        if (state.isOf(Blocks.SWEET_BERRY_BUSH)) {
            return Hazard.BERRY_BUSH;
        }
        if (state.isOf(Blocks.POWDER_SNOW)) {
            return Hazard.POWDER_SNOW;
        }
        if (state.isOf(Blocks.MAGMA_BLOCK) || isLitCampfire(state)) {
            return Hazard.HOT_FLOOR;
        }
        if (state.isOf(Blocks.WITHER_ROSE) || state.isOf(Blocks.POINTED_DRIPSTONE)) {
            return Hazard.OTHER;
        }
        return Hazard.NONE;
    }

    private static boolean isLitCampfire(BlockState state) {
        return (state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)) && state.get(Properties.LIT);
    }
}
