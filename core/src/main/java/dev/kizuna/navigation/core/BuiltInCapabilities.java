package dev.kizuna.navigation.core;

import java.util.ArrayList;
import java.util.List;

public final class BuiltInCapabilities {
    private static final int[][] DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final double SPECIAL_MOVE_PENALTY = 0.5;
    private static final List<MoveCapability> ALL = List.of(
            new GroundMove(MoveKind.WALK), new GroundMove(MoveKind.STEP_UP),
            new GroundMove(MoveKind.JUMP_UP), new GroundMove(MoveKind.DROP),
            new GroundMove(MoveKind.LEAP), new GroundMove(MoveKind.CLIMB),
            new GroundMove(MoveKind.SWIM), new GroundMove(MoveKind.DOOR));

    private BuiltInCapabilities() {
    }

    public static List<MoveCapability> all() {
        return ALL;
    }

    private record GroundMove(MoveKind kind) implements MoveCapability {
        @Override
        public List<MoveEdge> successors(ExpansionContext context) {
            // 固定方向和高度顺序，保证相同输入下的候选边顺序不变。
            List<MoveEdge> result = new ArrayList<>();
            if (kind.equals(MoveKind.CLIMB)) {
                addClimbs(context, result);
                return result;
            }
            for (int[] direction : DIRECTIONS) {
                if (kind.equals(MoveKind.LEAP)) {
                    addLeaps(context, direction, result);
                } else {
                    GridPos adjacent = context.origin().offset(direction[0], 0, direction[1]);
                    addAdjacent(context, adjacent, result);
                }
            }
            return result;
        }

        private void addAdjacent(ExpansionContext context, GridPos adjacent, List<MoveEdge> result) {
            // 扫描邻列的碰撞顶面，用实际高差区分走、跨台阶、跳高和下落。
            MovementLimits limits = context.profile().limits();
            int minOffset = -1;
            int maxOffset = 1;
            if (kind.equals(MoveKind.DROP)) {
                minOffset = -limits.safeDrop() - 1;
            } else if (kind.equals(MoveKind.STEP_UP)) {
                maxOffset = limits.stepHeight() + 1;
            } else if (kind.equals(MoveKind.JUMP_UP)) {
                maxOffset = limits.jumpHeight() + 1;
            }
            for (int offset = minOffset; offset <= maxOffset; offset++) {
                GridPos target = adjacent.offset(0, offset, 0);
                if (supported(context, target)) {
                    MoveEndpoints endpoints = endpoints(context, target);
                    if (matches(context, target, endpoints) && clear(context, endpoints)) {
                        addEdge(context, target, endpoints, result);
                    }
                }
            }
        }

        private boolean supported(ExpansionContext context, GridPos target) {
            // 游泳节点可以没有脚下支撑，其他地面节点必须有可站立顶面。
            Terrain terrain = context.grid().cellAt(target).terrain();
            if (kind.equals(MoveKind.SWIM) && terrain == Terrain.WATER
                    && context.profile().permits(MovementPermission.SWIM)) {
                return VoxelGeometry.canOccupy(context, target.center());
            }
            return VoxelGeometry.canStand(context, target);
        }

        private boolean matches(ExpansionContext context, GridPos target, MoveEndpoints endpoints) {
            // 水和门使用独立的权限段；其余地面段只按真实脚底高差分类。
            VoxelGrid grid = context.grid();
            Terrain from = grid.cellAt(context.origin()).terrain();
            Terrain to = grid.cellAt(target).terrain();
            boolean door = VoxelGeometry.isDoor(from) || VoxelGeometry.isDoor(to);
            boolean water = from == Terrain.WATER || to == Terrain.WATER;
            double rise = endpoints.to().y() - endpoints.from().y();
            double epsilon = VoxelGeometry.EPSILON;
            if (kind.equals(MoveKind.DOOR)) {
                return door && Math.abs(rise) <= epsilon;
            }
            if (kind.equals(MoveKind.SWIM)) {
                return water && !door && Math.abs(rise) <= epsilon;
            }
            if (door || water) {
                return false;
            }
            MovementLimits limits = context.profile().limits();
            int stepHeight = limits.stepHeight();
            if (kind.equals(MoveKind.WALK)) {
                return Math.abs(rise) <= epsilon;
            }
            if (kind.equals(MoveKind.STEP_UP)) {
                return rise > epsilon && rise <= stepHeight + epsilon;
            }
            if (kind.equals(MoveKind.JUMP_UP)) {
                return rise > stepHeight + epsilon && rise <= limits.jumpHeight() + epsilon;
            }
            return rise < -epsilon && -rise <= limits.safeDrop() + epsilon;
        }

        private boolean clear(ExpansionContext context, MoveEndpoints endpoints) {
            // 半格以内使用抬脚净空，更高的台阶必须验证实际起跳弹道。
            double rise = endpoints.to().y() - endpoints.from().y();
            if (kind.equals(MoveKind.JUMP_UP)
                    || (kind.equals(MoveKind.STEP_UP) && rise > VoxelGeometry.DIRECT_STEP_HEIGHT)) {
                return VoxelGeometry.clearJump(context, endpoints);
            }
            return VoxelGeometry.clearGround(context, endpoints);
        }

        private void addLeaps(ExpansionContext context, int[] direction, List<MoveEdge> result) {
            // 落点允许升高或降低，横向跨度仍按跨过的空列数限制。
            MovementLimits limits = context.profile().limits();
            int maxDistance = limits.maxGap() + 1;
            GridPos origin = context.origin();
            MotionPoint from = VoxelGeometry.feet(context, origin);
            for (int distance = 2; distance <= maxDistance; distance++) {
                GridPos column = origin.offset(direction[0] * distance, 0, direction[1] * distance);
                for (int offset = -limits.safeDrop() - 1; offset <= limits.jumpHeight() + 1; offset++) {
                    GridPos target = column.offset(0, offset, 0);
                    if (!VoxelGeometry.canStand(context, target)) {
                        continue;
                    }
                    MotionPoint to = VoxelGeometry.feet(context, target);
                    double rise = to.y() - from.y();
                    if (rise < -limits.safeDrop() - VoxelGeometry.EPSILON
                            || rise > limits.jumpHeight() + VoxelGeometry.EPSILON) {
                        continue;
                    }
                    MoveEndpoints endpoints = new MoveEndpoints(from, to);
                    if (VoxelGeometry.clearJump(context, endpoints)) {
                        addEdge(context, target, endpoints, result);
                    }
                }
            }
        }

        private void addClimbs(ExpansionContext context, List<MoveEdge> result) {
            // 攀爬只沿竖直轴，起点或落点至少一个必须具有可攀附介质。
            if (!context.profile().permits(MovementPermission.CLIMB)) {
                return;
            }
            GridPos origin = context.origin();
            VoxelGrid grid = context.grid();
            Terrain startTerrain = grid.cellAt(origin).terrain();
            for (int direction : new int[]{1, -1}) {
                GridPos target = origin.offset(0, direction, 0);
                Terrain targetTerrain = grid.cellAt(target).terrain();
                MoveEndpoints endpoints = endpoints(context, target);
                if ((startTerrain == Terrain.CLIMBABLE || targetTerrain == Terrain.CLIMBABLE)
                        && VoxelGeometry.clearLine(context, endpoints.from(), endpoints.to())) {
                    addEdge(context, target, endpoints, result);
                }
            }
        }

        private MoveEndpoints endpoints(ExpansionContext context, GridPos target) {
            MotionPoint from = VoxelGeometry.feet(context, context.origin());
            MotionPoint to = VoxelGeometry.feet(context, target);
            return new MoveEndpoints(from, to);
        }

        private void addEdge(ExpansionContext context, GridPos target, MoveEndpoints endpoints, List<MoveEdge> result) {
            // 同时保持整数节点启发值和实际运动距离的下界，斜向不会获得不合理折扣。
            GridPos origin = context.origin();
            double distance = Math.max(origin.distanceTo(target), endpoints.distance());
            double cost = distance;
            if (!kind.equals(MoveKind.WALK)) {
                cost += SPECIAL_MOVE_PENALTY;
            }
            result.add(new MoveEdge(origin, target, kind, cost, endpoints));
        }
    }
}
