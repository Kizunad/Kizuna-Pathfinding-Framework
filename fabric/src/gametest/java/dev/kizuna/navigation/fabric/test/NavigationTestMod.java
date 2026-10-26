package dev.kizuna.navigation.fabric.test;

import dev.kizuna.navigation.core.ExpansionContext;
import dev.kizuna.navigation.core.GridPos;
import dev.kizuna.navigation.core.MoveCapability;
import dev.kizuna.navigation.core.MoveEdge;
import dev.kizuna.navigation.core.MoveKind;
import dev.kizuna.navigation.core.MovementPermission;
import dev.kizuna.navigation.core.VoxelGeometry;
import dev.kizuna.navigation.fabric.KizunaNav;
import dev.kizuna.navigation.fabric.MoveProgress;
import net.fabricmc.api.ModInitializer;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;

import java.util.List;

public final class NavigationTestMod implements ModInitializer {
    private static final int STALL_DISTANCE = 2;
    private static final MoveKind STALL = new MoveKind("kizuna_navigation_test:stall");

    @Override
    public void onInitialize() {
        // 测试登记只在测试模组生效，发布包不改变任何原版实体类型。
        KizunaNav.register(EntityType.VINDICATOR, NavigationGameTest::profile);
        KizunaNav.register(EntityType.VILLAGER, NavigationLifecycleGameTest::doorProfile);
        Identifier id = new Identifier(STALL.id());
        KizunaNav.registerCapability(id, new StallCapability(), context -> () -> MoveProgress.RUNNING);
    }

    private static final class StallCapability implements MoveCapability {
        @Override
        public MoveKind kind() {
            return STALL;
        }

        @Override
        public List<MoveEdge> successors(ExpansionContext context) {
            // 只在专用参数组合中提供停滞段，其他用例继续使用普通移动能力。
            if (!context.profile().permits(MovementPermission.SWIM) || context.profile().limits().maxGap() != 0) {
                return List.of();
            }
            GridPos target = context.origin().offset(STALL_DISTANCE, 0, 0);
            if (!VoxelGeometry.canStand(context, target)
                    || !VoxelGeometry.clearLine(context, context.origin().center(), target.center())) {
                return List.of();
            }
            return List.of(new MoveEdge(context.origin(), target, STALL, STALL_DISTANCE));
        }
    }
}
