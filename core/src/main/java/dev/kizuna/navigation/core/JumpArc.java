package dev.kizuna.navigation.core;

import java.util.ArrayList;
import java.util.List;

/** 原版普通重力与空气阻力下的离散弹道；规划和执行共用同一初速度计算。 */
public record JumpArc(MotionPoint velocity, List<MotionPoint> points, int horizontalDelay) {
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double HORIZONTAL_DRAG = 0.91;
    private static final double MIN_ARC_HEIGHT = 1.25;
    private static final double LANDING_CLEARANCE = 0.75;
    private static final double VELOCITY_MARGIN = 0.12;
    private static final double DOUBLE_GRAVITY = 2 * GRAVITY;
    private static final int MAX_FLIGHT_TICKS = 160;

    public JumpArc(MotionPoint velocity, List<MotionPoint> points, int horizontalDelay) {
        // 保存独立的轨迹列表，执行器不能修改规划时校验过的采样点。
        this.velocity = velocity;
        this.points = List.copyOf(points);
        this.horizontalDelay = horizontalDelay;
    }

    public static JumpArc between(MotionPoint from, MotionPoint to) {
        // 先确定落地时刻，再反推水平初速度，规划与真实离散运动共用参数。
        double fromY = from.y();
        double targetY = to.y();
        double fromX = from.x();
        double fromZ = from.z();
        double rise = targetY - fromY;
        if (!Double.isFinite(rise) || Math.abs(rise) > MovementLimits.MAX_LIMIT) {
            throw new IllegalArgumentException("弹道高度超过移动上限");
        }
        double arcHeight = Math.max(MIN_ARC_HEIGHT, rise + LANDING_CLEARANCE);
        double velocityY = launchVelocity(arcHeight);
        List<Double> heights = flightHeights(velocityY, rise);
        int ticks = heights.size();
        int delay = horizontalDelay(heights, rise);
        int movingTicks = ticks - delay;
        double horizontalSum = (1 - Math.pow(HORIZONTAL_DRAG, movingTicks)) / (1 - HORIZONTAL_DRAG);
        double velocityX = (to.x() - fromX) / horizontalSum;
        double velocityZ = (to.z() - fromZ) / horizontalSum;
        List<MotionPoint> points = new ArrayList<>();
        points.add(from);
        for (int tick = 1; tick <= ticks; tick++) {
            int horizontalTick = Math.max(0, tick - delay);
            double progress = (1 - Math.pow(HORIZONTAL_DRAG, horizontalTick)) / (1 - HORIZONTAL_DRAG);
            double y;
            if (tick == ticks) {
                y = targetY;
            } else {
                y = fromY + heights.get(tick - 1);
            }
            points.add(new MotionPoint(fromX + velocityX * progress, y, fromZ + velocityZ * progress));
        }
        return new JumpArc(new MotionPoint(velocityX, velocityY, velocityZ), points, delay);
    }

    private static double launchVelocity(double arcHeight) {
        // 逐步增加初速度直至离散重力积分的顶点达到所需高度。
        double candidate = Math.sqrt(DOUBLE_GRAVITY * arcHeight) + VELOCITY_MARGIN;
        while (apexHeight(candidate) < arcHeight) {
            candidate += VELOCITY_MARGIN;
        }
        return candidate;
    }

    private static double apexHeight(double initialVelocity) {
        // 与游戏一致地先移动再施加重力和阻力，不能以连续抛物线代替。
        double height = 0;
        double velocity = initialVelocity;
        while (velocity > 0) {
            height += velocity;
            velocity = (velocity - GRAVITY) * VERTICAL_DRAG;
        }
        return height;
    }

    private static int horizontalDelay(List<Double> heights, double rise) {
        // 上升时先腾出平台侧面的净空，避免实体宽度在起跳后撞上台阶。
        if (rise <= 0) {
            return 0;
        }
        for (int index = 0; index < heights.size(); index++) {
            if (heights.get(index) >= rise) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("弹道无法达到落点高度");
    }

    private static List<Double> flightHeights(double initialVelocity, double landingHeight) {
        // 以游戏每 tick 的重力和阻力积分，下降穿过落点顶面时结束。
        List<Double> heights = new ArrayList<>();
        double height = 0;
        double velocity = initialVelocity;
        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            height += velocity;
            heights.add(height);
            if (velocity < 0 && height <= landingHeight) {
                return heights;
            }
            velocity = (velocity - GRAVITY) * VERTICAL_DRAG;
        }
        throw new IllegalArgumentException("弹道未能在限定时间落地");
    }
}
