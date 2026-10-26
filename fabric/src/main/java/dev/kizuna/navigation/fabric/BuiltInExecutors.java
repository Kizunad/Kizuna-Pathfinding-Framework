package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.JumpArc;
import dev.kizuna.navigation.core.GridPos;
import dev.kizuna.navigation.core.MoveEdge;
import dev.kizuna.navigation.core.MotionPoint;
import dev.kizuna.navigation.core.MoveKind;
import dev.kizuna.navigation.core.MoveEndpoints;
import dev.kizuna.navigation.core.MovementPermission;
import dev.kizuna.navigation.core.NavProfile;
import dev.kizuna.navigation.core.VoxelGeometry;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

final class BuiltInExecutors {
    private static final double ARRIVAL_RADIUS = 0.25;
    private static final double ARRIVAL_HEIGHT = 0.35;
    private static final double CLIMB_SPEED = 0.2;
    private static final int MAX_FLIGHT_TICKS = 160;

    private BuiltInExecutors() {
    }

    static MoveExecutorFactory factory(MoveKind kind) {
        // 规则与执行按移动类型对应，小台阶由原版移动控制处理。
        if (kind.equals(MoveKind.LEAP) || kind.equals(MoveKind.JUMP_UP)) {
            return Jump::new;
        }
        return context -> {
            MoveEdge edge = context.edge();
            MoveEndpoints endpoints = edge.endpoints();
            double rise = endpoints.to().y() - endpoints.from().y();
            if (kind.equals(MoveKind.STEP_UP) && rise > VoxelGeometry.DIRECT_STEP_HEIGHT) {
                return new Jump(context);
            }
            return new Ground(context);
        };
    }

    static void clearInput(MobEntity mob) {
        // 清理控制器的持续输入，防止上一段的加速影响新的弹道或取消状态。
        mob.getMoveControl().moveTo(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.setForwardSpeed(0);
        mob.setSidewaysSpeed(0);
        mob.setMovementSpeed(0);
    }

    static boolean reached(MoveContext context) {
        // 水平半径与实际落点高度同时满足才算到达，不能只比较整数节点。
        MobEntity mob = context.mob();
        MotionPoint target = context.edge().endpoints().to();
        double dx = mob.getX() - target.x();
        double dz = mob.getZ() - target.z();
        return dx * dx + dz * dz <= ARRIVAL_RADIUS * ARRIVAL_RADIUS
                && Math.abs(mob.getY() - target.y()) <= ARRIVAL_HEIGHT;
    }

    private static final class Ground implements MoveExecutor {
        private final MoveContext context;

        private Ground(MoveContext context) {
            this.context = context;
        }

        @Override
        public MoveProgress tick() {
            // 地面段持续驱动原版控制器，只有落在目标顶面后才交出控制权。
            MobEntity mob = context.mob();
            MoveEdge edge = context.edge();
            MoveKind kind = edge.kind();
            boolean swimming = kind.equals(MoveKind.SWIM);
            boolean climbing = kind.equals(MoveKind.CLIMB);
            boolean onGround = mob.isOnGround();
            if (reached(context) && (onGround || swimming || climbing)) {
                clearInput(mob);
                return MoveProgress.SUCCEEDED;
            }
            if (kind.equals(MoveKind.DOOR) && !prepareDoors()) {
                return MoveProgress.FAILED;
            }
            MotionPoint target = edge.endpoints().to();
            double targetY = target.y();
            mob.getMoveControl().moveTo(target.x(), targetY, target.z(), context.speed());
            if (swimming && mob.isTouchingWater()) {
                mob.getJumpControl().setActive();
            }
            if (climbing) {
                double vertical = CLIMB_SPEED;
                if (targetY < mob.getY()) {
                    vertical = -CLIMB_SPEED;
                }
                Vec3d velocity = mob.getVelocity();
                mob.setVelocity(velocity.x, vertical, velocity.z);
            }
            return MoveProgress.RUNNING;
        }

        private boolean prepareDoors() {
            MoveEdge edge = context.edge();
            BlockPos from = blockPos(edge.from());
            BlockPos to = blockPos(edge.to());
            return prepareDoor(from) && prepareDoor(to);
        }

        private boolean prepareDoor(BlockPos position) {
            // 木门优先按权限打开，破坏门仍受生物破坏游戏规则限制。
            MobEntity mob = context.mob();
            World world = mob.getWorld();
            NavProfile profile = context.profile();
            BlockState state = world.getBlockState(position);
            if (!(state.getBlock() instanceof DoorBlock door) || state.get(Properties.OPEN)) {
                return true;
            }
            if (state.isIn(BlockTags.WOODEN_DOORS) && profile.permits(MovementPermission.OPEN_DOORS)) {
                door.setOpen(mob, world, state, position, true /* open */);
                return true;
            }
            if (profile.permits(MovementPermission.BREAK_DOORS)
                    && world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
                return world.breakBlock(position, true /* drop */, mob);
            }
            return false;
        }

        @Override
        public void cancel() {
            clearInput(context.mob());
        }
    }

    private static final class Jump implements MoveExecutor {
        private final MoveContext context;
        private JumpArc arc;
        private int ticks;

        private Jump(MoveContext context) {
            this.context = context;
        }

        @Override
        public MoveProgress tick() {
            // 起跳后由真实重力积分，延迟横移和落地检查均沿共享弹道参数执行。
            MobEntity mob = context.mob();
            clearInput(mob);
            boolean onGround = mob.isOnGround();
            if (arc == null) {
                if (!onGround) {
                    return MoveProgress.RUNNING;
                }
                launch();
                return MoveProgress.RUNNING;
            }
            ticks++;
            if (onGround) {
                if (reached(context)) {
                    return MoveProgress.SUCCEEDED;
                }
                return MoveProgress.FAILED;
            }
            if (ticks == arc.horizontalDelay()) {
                MotionPoint velocity = arc.velocity();
                mob.setVelocity(velocity.x(), mob.getVelocity().y, velocity.z());
            }
            if (ticks > MAX_FLIGHT_TICKS) {
                return MoveProgress.FAILED;
            }
            return MoveProgress.RUNNING;
        }

        private void launch() {
            MobEntity mob = context.mob();
            MotionPoint from = new MotionPoint(mob.getX(), mob.getY(), mob.getZ());
            // 从实体当前脚底起跳，使用计划保存的真实落点高度。
            arc = JumpArc.between(from, context.edge().endpoints().to());
            MotionPoint velocity = arc.velocity();
            double velocityX = 0;
            double velocityZ = 0;
            if (arc.horizontalDelay() == 0) {
                velocityX = velocity.x();
                velocityZ = velocity.z();
            }
            mob.setOnGround(false);
            mob.setVelocity(velocityX, velocity.y(), velocityZ);
            mob.velocityModified = true;
        }

        @Override
        public void cancel() {
            clearInput(context.mob());
        }
    }

    private static BlockPos blockPos(GridPos position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }
}
