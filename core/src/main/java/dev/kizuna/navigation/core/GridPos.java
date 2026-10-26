package dev.kizuna.navigation.core;

public record GridPos(int x, int y, int z) {
    public GridPos offset(int dx, int dy, int dz) {
        return new GridPos(x + dx, y + dy, z + dz);
    }

    public double distanceTo(GridPos other) {
        // 先转为浮点再相减，避免远坐标的整数差溢出。
        double dx = (double) x - other.x;
        double dy = (double) y - other.y;
        double dz = (double) z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public MotionPoint center() {
        return new MotionPoint(x + MotionPoint.BLOCK_CENTER, y, z + MotionPoint.BLOCK_CENTER);
    }
}
