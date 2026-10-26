package dev.kizuna.navigation.fabric;

public interface MoveListener {
    MoveListener NONE = new MoveListener() { };

    default MoveDecision beforeMove(MoveContext context) {
        return MoveDecision.ALLOW;
    }

    default void completed(MoveContext context) {
    }

    default void failed(MoveContext context, MoveFailure reason) {
    }
}
