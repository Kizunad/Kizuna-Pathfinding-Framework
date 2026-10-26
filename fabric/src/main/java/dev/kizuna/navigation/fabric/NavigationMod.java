package dev.kizuna.navigation.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.mob.MobEntity;

public final class NavigationMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // 实体真正进入或离开服务端世界时接管和释放，构造阶段不触碰导航。
        ServerLifecycleEvents.SERVER_STARTING.register(server -> KizunaNav.freeze());
        ServerTickEvents.START_SERVER_TICK.register(NavigationScheduler::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(NavigationScheduler::shutdown);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof MobEntity mob) {
                KizunaNav.attachRegistered(mob);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof MobEntity mob && mob.getNavigation() instanceof KizunaNavigation) {
                KizunaNav.release(mob);
            }
        });
    }
}
