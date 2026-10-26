package dev.kizuna.navigation.core;

import java.util.List;

public final class VoxelGeometry {
    public static final double EPSILON = 0.00001;
    public static final double DIRECT_STEP_HEIGHT = 0.5;
    private static final double HALF = 0.5;
    private static final double SAMPLES_PER_BLOCK = 4;

    private VoxelGeometry() {
    }

    public static boolean canStand(ExpansionContext context, GridPos position) {
        // 节点标识使用支撑方块上一格，脚底则落在实际碰撞顶面。
        double height = standingHeight(context, position);
        if (!context.grid().bounds().contains(position) || !Double.isFinite(height)) {
            return false;
        }
        MotionPoint center = position.center();
        return canOccupy(context, new MotionPoint(center.x(), height, center.z()));
    }

    public static MotionPoint feet(ExpansionContext context, GridPos position) {
        // 支撑存在时返回实际顶面，无支撑的水中或攀爬节点保留格点高度。
        double height = standingHeight(context, position);
        MotionPoint center = position.center();
        if (!Double.isFinite(height)) {
            return center;
        }
        return new MotionPoint(center.x(), height, center.z());
    }

    private static double standingHeight(ExpansionContext context, GridPos position) {
        // 宽实体的每个覆盖列均须有支撑，局部台阶允许由其中较高的顶面承重。
        MotionPoint center = position.center();
        double width = context.profile().body().width();
        int minX = lower(center.x(), width);
        int maxX = upper(center.x(), width);
        int minZ = lower(center.z(), width);
        int maxZ = upper(center.z(), width);
        double height = Double.NEGATIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                GridPos support = new GridPos(x, position.y() - 1, z);
                double top = supportTop(context, center, support);
                if (!Double.isFinite(top)) {
                    return Double.NaN;
                }
                height = Math.max(height, top);
            }
        }
        return height;
    }

    private static double supportTop(ExpansionContext context, MotionPoint center, GridPos support) {
        // 只采用身体水平投影内的碰撞箱顶面，避免旁侧高块虚增脚底高度。
        double halfWidth = context.profile().body().width() * HALF;
        double top = Double.NEGATIVE_INFINITY;
        List<CollisionBox> collisions = context.grid().cellAt(support).collisions();
        for (CollisionBox box : collisions) {
            if (overlapsHorizontal(box, support, center, halfWidth)) {
                top = Math.max(top, support.y() + box.max().y());
            }
        }
        return top;
    }

    public static boolean canOccupy(ExpansionContext context, MotionPoint feet) {
        NavProfile profile = context.profile();
        BodySize body = profile.body();
        VoxelGrid grid = context.grid();
        GridBounds bounds = grid.bounds();
        double width = body.width();
        double feetX = feet.x();
        double feetY = feet.y();
        double feetZ = feet.z();
        int minX = lower(feetX, width);
        int maxX = upper(feetX, width);
        int minZ = lower(feetZ, width);
        int maxZ = upper(feetZ, width);
        // 额外检查脚下方块，覆盖矮方块与高出原格子的栅栏碰撞。
        int minY = (int) Math.floor(feetY + EPSILON) - 1;
        int maxY = (int) Math.floor(feetY + body.height() - EPSILON);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    GridPos block = new GridPos(x, y, z);
                    Cell cell = grid.cellAt(block);
                    if ((!bounds.contains(block) && y >= feetY)
                            || !canPass(cell.terrain(), profile) || collides(cell, block, feet, body)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean collides(Cell cell, GridPos block, MotionPoint feet, BodySize body) {
        // 门的打开与破坏由独立权限和执行器处理，保留可通行介质的几何检查。
        if (isDoor(cell.terrain())) {
            return false;
        }
        double halfWidth = body.width() * HALF;
        double feetY = feet.y();
        double bodyTop = feetY + body.height();
        int blockY = block.y();
        for (CollisionBox box : cell.collisions()) {
            if (overlapsHorizontal(box, block, feet, halfWidth)
                    && bodyTop > blockY + box.min().y() + EPSILON
                    && feetY < blockY + box.max().y() - EPSILON) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsHorizontal(CollisionBox box, GridPos block, MotionPoint feet, double halfWidth) {
        // 两轴都须有正宽度的重叠，刚好接触边缘不算占据实体空间。
        double feetX = feet.x();
        double feetZ = feet.z();
        int blockX = block.x();
        int blockZ = block.z();
        MotionPoint min = box.min();
        MotionPoint max = box.max();
        return feetX + halfWidth > blockX + min.x() + EPSILON
                && feetX - halfWidth < blockX + max.x() - EPSILON
                && feetZ + halfWidth > blockZ + min.z() + EPSILON
                && feetZ - halfWidth < blockZ + max.z() - EPSILON;
    }

    public static boolean clearLine(ExpansionContext context, MotionPoint from, MotionPoint to) {
        // 沿扫掠体积采样，并在同时跨越两条网格线时检查两侧，禁止斜穿方块角。
        double fromX = from.x();
        double fromY = from.y();
        double fromZ = from.z();
        double dx = to.x() - fromX;
        double dy = to.y() - fromY;
        double dz = to.z() - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int samples = Math.max(1, (int) Math.ceil(distance * SAMPLES_PER_BLOCK));
        MotionPoint previous = from;
        for (int index = 0; index <= samples; index++) {
            double fraction = (double) index / samples;
            MotionPoint feet = new MotionPoint(fromX + dx * fraction, fromY + dy * fraction,
                    fromZ + dz * fraction);
            if (!canOccupy(context, feet) || !clearCorner(context, previous, feet)) {
                return false;
            }
            previous = feet;
        }
        return true;
    }

    private static boolean clearCorner(ExpansionContext context, MotionPoint from, MotionPoint to) {
        // 同时跨越两个轴时两侧均须容身，窄实体也不能从封闭方块角穿过。
        int fromX = (int) Math.floor(from.x());
        int fromZ = (int) Math.floor(from.z());
        int toX = (int) Math.floor(to.x());
        int toZ = (int) Math.floor(to.z());
        if (fromX == toX || fromZ == toZ) {
            return true;
        }
        MotionPoint sideX = new MotionPoint(toX + HALF, to.y(), fromZ + HALF);
        MotionPoint sideZ = new MotionPoint(fromX + HALF, to.y(), toZ + HALF);
        return canOccupy(context, sideX) && canOccupy(context, sideZ);
    }

    public static boolean clearGround(ExpansionContext context, MoveEndpoints endpoints) {
        // 小台阶先抬脚再平移；下落先越过边缘，不能用穿过台阶侧面的直线替代。
        MotionPoint from = endpoints.from();
        MotionPoint to = endpoints.to();
        double top = Math.max(from.y(), to.y());
        MotionPoint lifted = new MotionPoint(from.x(), top, from.z());
        MotionPoint overEdge = new MotionPoint(to.x(), top, to.z());
        return clearLine(context, from, lifted) && clearLine(context, lifted, overEdge)
                && clearLine(context, overEdge, to);
    }

    public static boolean clearJump(ExpansionContext context, GridPos target) {
        MoveEndpoints endpoints = new MoveEndpoints(feet(context, context.origin()), feet(context, target));
        return clearJump(context, endpoints);
    }

    public static boolean clearJump(ExpansionContext context, MoveEndpoints endpoints) {
        // 弹道与执行器共用真实脚底端点，净空包含整个身体而非节点中心。
        JumpArc arc = JumpArc.between(endpoints.from(), endpoints.to());
        List<MotionPoint> points = arc.points();
        for (int index = 1; index < points.size(); index++) {
            MotionPoint from = points.get(index - 1);
            MotionPoint to = points.get(index);
            if (!clearLine(context, from, to)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isDoor(Terrain terrain) {
        return terrain == Terrain.OPEN_DOOR || terrain == Terrain.WOOD_DOOR || terrain == Terrain.IRON_DOOR;
    }

    private static boolean canPass(Terrain terrain, NavProfile profile) {
        // 介质权限独立于碰撞形状，开门和破门拥有不同的准入条件。
        boolean canBreakDoors = profile.permits(MovementPermission.BREAK_DOORS);
        return switch (terrain) {
            case WATER -> profile.permits(MovementPermission.SWIM);
            case WOOD_DOOR -> profile.permits(MovementPermission.OPEN_DOORS)
                    || canBreakDoors;
            case IRON_DOOR -> canBreakDoors;
            default -> true;
        };
    }

    private static int lower(double center, double width) {
        return (int) Math.floor(center - width * HALF + EPSILON);
    }

    private static int upper(double center, double width) {
        return (int) Math.floor(center + width * HALF - EPSILON);
    }
}
