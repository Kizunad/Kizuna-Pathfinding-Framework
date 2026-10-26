package dev.kizuna.navigation.core;

/** maxNodes 同时限制节点扩展与按 CELL_READS_PER_UNIT 折算的首次网格读取。 */
public record SearchBudget(int maxNodes, int maxDistance) {
    public static final int CELL_READS_PER_UNIT = 32;

    public SearchBudget {
        // 预算与搜索半径均必须有正值，不能创建永远无法被调度的请求。
        if (maxNodes <= 0 || maxDistance <= 0) {
            throw new IllegalArgumentException("搜索节点数和范围必须为正数");
        }
    }

    public static int workUnits(int visitedNodes, int readCells) {
        // 一组首次读取折算为一个节点额度；缓存命中不重复收费。
        return visitedNodes + (readCells + CELL_READS_PER_UNIT - 1) / CELL_READS_PER_UNIT;
    }
}
