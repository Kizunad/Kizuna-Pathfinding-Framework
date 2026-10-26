package dev.kizuna.navigation.fabric;

import dev.kizuna.navigation.core.NavResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class NavTicket {
    private final CompletableFuture<NavResult> future = new CompletableFuture<>();
    private final Runnable threadCheck;

    NavTicket(Runnable threadCheck) {
        this.threadCheck = threadCheck;
    }

    public CompletionStage<NavResult> result() {
        return future.minimalCompletionStage();
    }

    public boolean cancel() {
        threadCheck.run();
        return future.cancel(false /* mayInterruptIfRunning */);
    }

    public boolean isCancelled() {
        return future.isCancelled();
    }

    void complete(NavResult result) {
        future.complete(result);
    }

    void fail(Throwable error) {
        future.completeExceptionally(error);
    }
}
