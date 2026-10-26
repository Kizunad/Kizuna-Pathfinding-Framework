package dev.kizuna.navigation.fabric.mixin;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MobEntity.class)
public interface MobNavigationAccessor {
    @Accessor("navigation")
    void kizunaNavigation$setNavigation(EntityNavigation navigation);
}
