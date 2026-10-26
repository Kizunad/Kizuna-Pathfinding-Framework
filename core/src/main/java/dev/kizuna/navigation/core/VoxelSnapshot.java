package dev.kizuna.navigation.core;

import java.util.Objects;
import java.util.function.Function;

public final class VoxelSnapshot implements VoxelGrid {
    private static final int MAX_CELLS = 2_000_000;
    private final GridBounds bounds;
    private final int sizeX;
    private final int sizeY;
    private final Cell[] cells;

    private VoxelSnapshot(GridBounds bounds) {
        // 先用长整型核对体积上限，再分配有界数组防止尺寸溢出。
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        GridPos min = bounds.min();
        GridPos max = bounds.max();
        sizeX = Math.toIntExact((long) max.x() - min.x() + 1);
        sizeY = Math.toIntExact((long) max.y() - min.y() + 1);
        int sizeZ = Math.toIntExact((long) max.z() - min.z() + 1);
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume <= 0 || volume > MAX_CELLS) {
            throw new IllegalArgumentException("快照体积超过上限");
        }
        cells = new Cell[(int) volume];
    }

    public static VoxelSnapshot capture(GridBounds bounds, Function<GridPos, Cell> reader) {
        // 显式快照按固定顺序复制所有单元，适用于已准备好的离线数据。
        Objects.requireNonNull(reader, "reader");
        VoxelSnapshot snapshot = new VoxelSnapshot(bounds);
        GridPos min = bounds.min();
        int sizeZ = snapshot.cells.length / snapshot.sizeX / snapshot.sizeY;
        for (int z = 0; z < sizeZ; z++) {
            for (int y = 0; y < snapshot.sizeY; y++) {
                for (int x = 0; x < snapshot.sizeX; x++) {
                    GridPos position = min.offset(x, y, z);
                    snapshot.cells[snapshot.index(position)] = Objects.requireNonNull(reader.apply(position), "cell");
                }
            }
        }
        return snapshot;
    }

    @Override
    public GridBounds bounds() {
        return bounds;
    }

    @Override
    public Cell cellAt(GridPos position) {
        // 边界外直接封闭，不允许数组访问或生成范围外节点。
        if (!bounds.contains(position)) {
            return Cell.SOLID;
        }
        return cells[index(position)];
    }

    private int index(GridPos position) {
        GridPos min = bounds.min();
        return ((position.z() - min.z()) * sizeY + position.y() - min.y()) * sizeX + position.x() - min.x();
    }
}
