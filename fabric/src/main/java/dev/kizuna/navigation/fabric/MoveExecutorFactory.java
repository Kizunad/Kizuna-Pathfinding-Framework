package dev.kizuna.navigation.fabric;

@FunctionalInterface
public interface MoveExecutorFactory {
    MoveExecutor create(MoveContext context);
}
