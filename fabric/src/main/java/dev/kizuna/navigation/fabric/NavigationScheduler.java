package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.NavRequest;
import dev.kizuna.navigation.core.NavResult;
import net.minecraft.server.MinecraftServer;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.PriorityQueue;

final class NavigationScheduler {
    private static final Map<MinecraftServer, NavigationScheduler> SERVERS = new IdentityHashMap<>();
    private final PriorityQueue<Queued> queue = new PriorityQueue<>(Comparator.comparing(Queued::priority)
            .thenComparingLong(Queued::sequence));
    private long sequence;

    private NavigationScheduler() {
    }

    static void enqueue(KizunaNavigation navigation, NavRequest request, NavPriority priority, NavTicket ticket) {
        // 清理已取消项后限制队列长度，优先级相同的请求按提交顺序处理。
        MinecraftServer server = navigation.server();
        NavigationScheduler scheduler = SERVERS.computeIfAbsent(server, ignored -> new NavigationScheduler());
        scheduler.queue.removeIf(entry -> entry.ticket().isCancelled());
        if (scheduler.queue.size() >= KizunaNav.settings().maxQueuedRequests()) {
            throw new IllegalStateException("导航请求队列已满");
        }
        scheduler.queue.add(new Queued(navigation, request, priority, ticket, scheduler.sequence++));
    }

    static void tick(MinecraftServer server) {
        // 各服务器独立消费队列，尚无请求时不分配调度器。
        NavigationScheduler scheduler = SERVERS.get(server);
        if (scheduler != null) {
            scheduler.drain();
        }
    }

    private void drain() {
        // 为请求预留完整工作额度，结算时合计扩展节点与首次读取的格子。
        int remainingWork = KizunaNav.settings().nodesPerTick();
        int queuedAtStart = queue.size();
        for (int index = 0; index < queuedAtStart && !queue.isEmpty(); index++) {
            Queued entry = queue.peek();
            KizunaNavigation navigation = entry.navigation();
            NavRequest request = entry.request();
            NavTicket ticket = entry.ticket();
            int requestWork = request.budget().maxNodes();
            if (ticket.isCancelled() || !navigation.isAttached()) {
                queue.remove();
                ticket.cancel();
                continue;
            }
            if (requestWork > remainingWork) {
                return;
            }
            queue.remove();
            try {
                KizunaNavigation.PlanningResult calculation = navigation.calculate(request);
                NavResult result = calculation.result();
                remainingWork -= result.workUnits();
                navigation.accept(ticket, request, calculation);
            } catch (RuntimeException error) {
                remainingWork -= requestWork;
                navigation.reject(ticket, error);
                KizunaNav.LOGGER.error("导航规划失败", error);
            }
        }
    }

    static void shutdown(MinecraftServer server) {
        // 服务器停止后取消所有等待结果，避免句柄和世界被队列继续持有。
        NavigationScheduler scheduler = SERVERS.remove(server);
        if (scheduler != null) {
            for (Queued entry : scheduler.queue) {
                entry.ticket().cancel();
            }
        }
    }

    private record Queued(KizunaNavigation navigation, NavRequest request, NavPriority priority,
                          NavTicket ticket, long sequence) {
    }
}
