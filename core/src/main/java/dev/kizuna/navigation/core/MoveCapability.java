package dev.kizuna.navigation.core;

import java.util.List;

/** 实现须线程安全，并按固定顺序返回后继；碰撞与移动可行性由能力实现保证。 */
public interface MoveCapability {
    MoveKind kind();

    List<MoveEdge> successors(ExpansionContext context);
}
