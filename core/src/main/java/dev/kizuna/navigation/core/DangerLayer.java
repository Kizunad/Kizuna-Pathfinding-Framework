package dev.kizuna.navigation.core;

import java.util.List;

public final class DangerLayer implements CostLayer {
    public static final DangerLayer INSTANCE = new DangerLayer();
    private static final double HALF = 0.5;
    private static final double EPSILON = 0.00001;

    private DangerLayer() {
    }

    @Override
    public double cost(NodeContext context) {
        MoveEdge edge = context.edge();
        MoveKind kind = edge.kind();
        // 危险判定沿实际脚底轨迹采样，与矮方块的碰撞顶面保持一致。
        MoveEndpoints endpoints = edge.endpoints();
        MotionPoint target = endpoints.to();
        List<MotionPoint> samples;
        if (kind.equals(MoveKind.DROP) && hasDropHazard(context)) {
            return Double.POSITIVE_INFINITY;
        }
        double rise = target.y() - endpoints.from().y();
        if (kind.equals(MoveKind.LEAP) || kind.equals(MoveKind.JUMP_UP)
                || (kind.equals(MoveKind.STEP_UP) && rise > VoxelGeometry.DIRECT_STEP_HEIGHT)) {
            samples = JumpArc.between(endpoints.from(), target).points();
        } else {
            samples = List.of(endpoints.from(), target);
        }
        for (MotionPoint sample : samples) {
            if (hasHazard(context, sample)) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return 0;
    }

    private boolean hasDropHazard(NodeContext context) {
        // 沿落点列覆盖整个下降高度，安全落差不能掩盖途中的危险介质。
        MoveEdge edge = context.edge();
        MotionPoint target = edge.endpoints().to();
        double fromY = edge.endpoints().from().y();
        for (double y = target.y(); y <= fromY; y += HALF) {
            MotionPoint feet = new MotionPoint(target.x(), y, target.z());
            if (hasHazard(context, feet)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHazard(NodeContext context, MotionPoint feet) {
        // 检查身体覆盖体积和脚下支撑，兼顾接触危险与灼热地面。
        BodySize body = context.profile().body();
        double halfWidth = body.width() * HALF;
        double feetX = feet.x();
        double feetY = feet.y();
        double feetZ = feet.z();
        int minX = (int) Math.floor(feetX - halfWidth + EPSILON);
        int maxX = (int) Math.floor(feetX + halfWidth - EPSILON);
        int minZ = (int) Math.floor(feetZ - halfWidth + EPSILON);
        int maxZ = (int) Math.floor(feetZ + halfWidth - EPSILON);
        int minY = (int) Math.floor(feetY) - 1;
        int maxY = (int) Math.floor(feetY + body.height() - EPSILON);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (context.grid().cellAt(new GridPos(x, y, z)).hazard() != Hazard.NONE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
