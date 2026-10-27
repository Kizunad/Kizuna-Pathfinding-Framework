package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.BuiltInCapabilities;
import dev.kizuna.navigation.core.CostLayer;
import dev.kizuna.navigation.core.MoveCapability;
import dev.kizuna.navigation.core.MoveKind;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import dev.kizuna.navigation.fabric.mixin.MobNavigationAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class KizunaNav {
    static final Logger LOGGER = LoggerFactory.getLogger("kizuna_navigation");
    private static final Map<EntityType<?>, ProfileProvider> PROFILES = new HashMap<>();
    private static final Map<EntityType<?>, List<CostLayer>> COSTS = new HashMap<>();
    private static final Map<MoveKind, MoveCapability> CAPABILITIES = new LinkedHashMap<>();
    private static final Map<MoveKind, MoveExecutorFactory> EXECUTORS = new HashMap<>();
    private static NavigationSettings settings = NavigationSettings.DEFAULT;
    private static boolean frozen;

    static {
        // 为每个内置移动能力安装匹配执行器，扩展仍走相同注册表。
        for (MoveCapability capability : BuiltInCapabilities.all()) {
            MoveKind kind = capability.kind();
            CAPABILITIES.put(kind, capability);
            EXECUTORS.put(kind, BuiltInExecutors.factory(kind));
        }
    }

    private KizunaNav() {
    }

    public static void register(EntityType<?> type, ProfileProvider profiles) {
        // 整类登记只在初始化期接受一次，避免运行中改变实体接管策略。
        requireRegistrationOpen();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(profiles, "profiles");
        if (PROFILES.putIfAbsent(type, profiles) != null) {
            throw new IllegalArgumentException("实体类型已登记：" + EntityType.getId(type));
        }
    }

    public static void addCostLayer(EntityType<?> type, CostLayer layer) {
        // 类型代价按登记顺序追加，运行时由每个实体请求读取同一规则。
        requireRegistrationOpen();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(layer, "layer");
        COSTS.computeIfAbsent(type, ignored -> new ArrayList<>()).add(layer);
    }

    public static void registerCapability(Identifier id, MoveCapability capability, MoveExecutorFactory executor) {
        // 规则和执行器必须成对登记，禁止覆盖内置类型或留下无法执行的边。
        requireRegistrationOpen();
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(executor, "executor");
        MoveKind kind = new MoveKind(id.toString());
        if (!kind.equals(capability.kind()) || CAPABILITIES.containsKey(kind)) {
            throw new IllegalArgumentException("移动类型不匹配或已登记：" + id);
        }
        CAPABILITIES.put(kind, capability);
        EXECUTORS.put(kind, executor);
    }

    public static void configure(NavigationSettings configuration) {
        requireRegistrationOpen();
        settings = Objects.requireNonNull(configuration, "configuration");
    }

    public static NavigationSettings settings() {
        return settings;
    }

    public static NavHandle takeOver(MobEntity mob, ProfileProvider profiles) {
        // 先验证原导航归属，再停止并替换；重复接管返回同一托管对象。
        requireServerThread(mob);
        Objects.requireNonNull(profiles, "profiles");
        EntityNavigation original = mob.getNavigation();
        if (original instanceof KizunaNavigation managed) {
            return managed;
        }
        validateOriginalNavigation(original);
        original.stop();
        KizunaNavigation managed = new KizunaNavigation(mob, profiles, original);
        ((MobNavigationAccessor) mob).kizunaNavigation$setNavigation(managed);
        return managed;
    }

    public static void release(MobEntity mob) {
        // 先还原原实例再取消任务，取消回调不能向已释放的句柄提交新请求。
        requireServerThread(mob);
        if (mob.getNavigation() instanceof KizunaNavigation managed) {
            ((MobNavigationAccessor) mob).kizunaNavigation$setNavigation(managed.originalNavigation());
            managed.stop();
        }
    }

    public static NavHandle of(MobEntity mob) {
        // 只返回已经取得控制权的句柄，避免读取操作隐式改变实体行为。
        requireServerThread(mob);
        if (mob.getNavigation() instanceof KizunaNavigation managed) {
            return managed;
        }
        throw new IllegalStateException("实体未被托管");
    }

    public static void attachRegistered(MobEntity mob) {
        // 仅在实体主线程加载入口接管，已有托管和未登记实体保持幂等。
        EntityType<?> type = mob.getType();
        ProfileProvider profiles = PROFILES.get(type);
        if (profiles == null || mob.getWorld().isClient || mob.getNavigation() instanceof KizunaNavigation) {
            return;
        }
        try {
            takeOver(mob, profiles);
        } catch (IllegalArgumentException conflict) {
            LOGGER.warn("跳过实体类型 {} 的导航接管：{}", EntityType.getId(type), conflict.getMessage());
        }
    }

    static void requireServerThread(MobEntity mob) {
        // 世界读取和实体控制均受主线程约束，单体接管也不能绕过检查。
        if (!(mob.getWorld() instanceof ServerWorld world) || !world.getServer().isOnThread()) {
            throw new IllegalStateException("导航 API 只能在服务端主线程调用");
        }
    }

    static List<MoveCapability> capabilities() {
        return List.copyOf(CAPABILITIES.values());
    }

    static MoveExecutorFactory executor(MoveKind kind) {
        return Objects.requireNonNull(EXECUTORS.get(kind), "未登记移动执行器：" + kind.id());
    }

    static List<CostLayer> costLayers(EntityType<?> type) {
        return List.copyOf(COSTS.getOrDefault(type, List.of()));
    }

    static void freeze() {
        frozen = true;
    }

    private static void requireRegistrationOpen() {
        // 服务器启动后固定登记表，避免已有实体与后续加载实体行为不一致。
        if (frozen) {
            throw new IllegalStateException("须在模组初始化期间完成导航登记");
        }
    }

    private static void validateOriginalNavigation(EntityNavigation original) {
        // 只替换原版地面实现，尊重其他模组或游泳飞行导航的控制权。
        String className = original.getClass().getName();
        if (!(original instanceof MobNavigation) || !className.startsWith("net.minecraft.")) {
            throw new IllegalArgumentException("只接管原版地面导航，当前为 " + className);
        }
    }
}
