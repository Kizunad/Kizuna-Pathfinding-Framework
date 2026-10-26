package dev.kizuna.navigation.core;

/** lowerBound 必须是不高估剩余代价的下界；anchor 用于近距离请求的范围校验。 */
public interface NavGoal {
    GridPos anchor();

    boolean reached(GridPos position);

    double lowerBound(GridPos position);
}
