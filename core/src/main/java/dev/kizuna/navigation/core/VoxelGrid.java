package dev.kizuna.navigation.core;

/** 搜索只读网格；边界外封闭。A* 对单次规划中的首次访问缓存并计入工作额度。 */
public interface VoxelGrid {
    GridBounds bounds();

    Cell cellAt(GridPos position);
}
