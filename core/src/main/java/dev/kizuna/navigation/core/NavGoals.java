package dev.kizuna.navigation.core;

import java.util.Objects;

public final class NavGoals {
    private NavGoals() {
    }

    public static NavGoal point(GridPos target) {
        return within(target, 0);
    }

    public static NavGoal within(GridPos target, double radius) {
        validateRadius(radius);
        return new Point(Objects.requireNonNull(target, "target"), radius);
    }

    public static NavGoal region(GridBounds bounds) {
        return new Region(Objects.requireNonNull(bounds, "bounds"));
    }

    public static NavGoal awayFrom(GridPos threat, double radius) {
        validateRadius(radius);
        return new Away(Objects.requireNonNull(threat, "threat"), radius);
    }

    private static void validateRadius(double radius) {
        // 半径必须是有限非负数，零半径用于精确格点目标。
        if (!Double.isFinite(radius) || radius < 0) {
            throw new IllegalArgumentException("目标半径必须为有限非负数");
        }
    }

    private record Point(GridPos anchor, double radius) implements NavGoal {
        @Override
        public boolean reached(GridPos position) {
            return position.distanceTo(anchor) <= radius;
        }

        @Override
        public double lowerBound(GridPos position) {
            return Math.max(0, position.distanceTo(anchor) - radius);
        }
    }

    private record Region(GridBounds bounds) implements NavGoal {
        @Override
        public GridPos anchor() {
            return bounds.min();
        }

        @Override
        public boolean reached(GridPos position) {
            return bounds.contains(position);
        }

        @Override
        public double lowerBound(GridPos position) {
            // 把当前位置夹到区域内最近的点，所得距离不会高估剩余代价。
            GridPos min = bounds.min();
            GridPos max = bounds.max();
            int x = Math.max(min.x(), Math.min(position.x(), max.x()));
            int y = Math.max(min.y(), Math.min(position.y(), max.y()));
            int z = Math.max(min.z(), Math.min(position.z(), max.z()));
            return position.distanceTo(new GridPos(x, y, z));
        }
    }

    private record Away(GridPos anchor, double radius) implements NavGoal {
        @Override
        public boolean reached(GridPos position) {
            return position.distanceTo(anchor) >= radius;
        }

        @Override
        public double lowerBound(GridPos position) {
            return Math.max(0, radius - position.distanceTo(anchor));
        }
    }
}
