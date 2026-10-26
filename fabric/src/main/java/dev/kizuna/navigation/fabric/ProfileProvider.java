package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.NavProfile;
import net.minecraft.entity.mob.MobEntity;

@FunctionalInterface
public interface ProfileProvider {
    NavProfile profileFor(MobEntity mob);
}
