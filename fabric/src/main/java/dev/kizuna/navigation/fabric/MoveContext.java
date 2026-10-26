package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.MoveEdge;
import dev.kizuna.navigation.core.NavProfile;
import net.minecraft.entity.mob.MobEntity;

public record MoveContext(MobEntity mob, MoveEdge edge, NavProfile profile, double speed) {
}
