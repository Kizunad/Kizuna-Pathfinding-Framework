package dev.kizuna.navigation.fabric;

/** 工厂每段创建一个实例；方法均在服务端主线程调用。 */
public interface MoveExecutor {
    MoveProgress tick();

    default void cancel() {
    }
}
