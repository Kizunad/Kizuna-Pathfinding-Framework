package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.NavPlan;
import dev.kizuna.navigation.core.NavProfile;
import dev.kizuna.navigation.core.NavRequest;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public interface NavHandle {
    NavProfile currentProfile();

    NavRequest requestTo(BlockPos target);

    NavTicket planAsync(NavRequest request);

    NavTicket planAsync(NavRequest request, NavPriority priority);

    NavTicket navigateTo(BlockPos target, double speed);

    void setListener(MoveListener listener);

    Optional<NavPlan> currentPlan();

    int replanCount();

    NavState state();

    void stop();
}
