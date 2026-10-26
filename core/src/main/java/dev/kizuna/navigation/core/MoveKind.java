package dev.kizuna.navigation.core;

import java.util.Objects;
import java.util.regex.Pattern;

public record MoveKind(String id) {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    public static final MoveKind WALK = builtin("walk");
    public static final MoveKind STEP_UP = builtin("step_up");
    public static final MoveKind JUMP_UP = builtin("jump_up");
    public static final MoveKind DROP = builtin("drop");
    public static final MoveKind LEAP = builtin("leap");
    public static final MoveKind CLIMB = builtin("climb");
    public static final MoveKind SWIM = builtin("swim");
    public static final MoveKind DOOR = builtin("door");

    public MoveKind {
        // 验证命名空间格式，使扩展类型在注册表和执行器之间保持唯一标识。
        Objects.requireNonNull(id, "id");
        if (!IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("移动类型必须为命名空间 ID");
        }
    }

    private static MoveKind builtin(String path) {
        return new MoveKind("kizuna_navigation:" + path);
    }
}
