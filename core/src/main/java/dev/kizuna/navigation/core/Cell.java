package dev.kizuna.navigation.core;

import java.util.Objects;
import java.util.List;

public record Cell(Terrain terrain, Hazard hazard, List<CollisionBox> collisions) {
    public static final Cell AIR = new Cell(Terrain.AIR, Hazard.NONE);
    public static final Cell SOLID = new Cell(Terrain.SOLID, Hazard.NONE);

    public Cell(Terrain terrain, Hazard hazard, List<CollisionBox> collisions) {
        // 形状随单元保存，避免外部修改使同一次规划中的碰撞结果变化。
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.hazard = Objects.requireNonNull(hazard, "hazard");
        this.collisions = List.copyOf(collisions);
    }

    public Cell(Terrain terrain, Hazard hazard) {
        this(terrain, hazard, defaultCollisions(terrain));
    }

    private static List<CollisionBox> defaultCollisions(Terrain terrain) {
        // 保留旧的整格构造入口，只有普通实心地形默认占满一格。
        if (terrain == Terrain.SOLID) {
            return List.of(CollisionBox.FULL);
        }
        return List.of();
    }
}
