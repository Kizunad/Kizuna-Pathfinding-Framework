# 公开 API

## 纯 Java 核心

包名：`dev.kizuna.navigation.core`。生产依赖为空，不引用 Minecraft 或 Fabric。

| API | 契约 |
| --- | --- |
| `GridPos`、`GridBounds`、`VoxelGrid`、`VoxelSnapshot` | 有界只读网格；A* 按需缓存；显式 `VoxelSnapshot.capture` 仍复制完整离线网格 |
| `Cell`、`CollisionBox`、`Terrain`、`Hazard` | 单元的局部碰撞箱、介质与危险属性；碰撞箱列表不可变 |
| `NavProfile`、`BodySize`、`MovementLimits`、`MovementPermission` | 不可变的宽高、台阶、跳高、安全下落、跨空格上限和权限 |
| `MoveKind`、`MoveCapability`、`ExpansionContext` | 命名空间移动类型与按固定顺序生成后继的规则 |
| `CostLayer`、`NodeContext`、`SamplingPrecision` | 非负附加代价；正无穷代表禁止；精度声明供后续分层规划使用 |
| `DangerLayer`、`AvoidLayer`、`PreferLayer` | 危险禁止、靠近指定点的惩罚、区域偏好惩罚 |
| `NavGoals.point/within/region/awayFrom` | 到点、进入半径、进入区域、远离某点 |
| `NavRequest`、`SearchBudget` | 起点、目标、参数、代价层和包含读取开销的工作/距离预算 |
| `AStarPlanner.plan` | 搜索；可额外传入当前请求内屏蔽的 `Set<MoveKey>` |
| `NavResult`、`SearchStatus`、`NavPlan`、`MoveEdge` | 明确的成功、无路、预算耗尽、起点无效、目标超范围状态及分段路径 |
| `JumpArc`、`VoxelGeometry` | 内置与自定义能力共用的弹道与净空计算 |
| `MoveEndpoints`、`MoveEdge.endpoints()` | 保存真实脚底坐标，供碰撞、危险层和执行器共同使用 |
| `NavResult.readCells()`、`workUnits()` | 本次规划首次读取的格数，以及扩展与读取合计消耗的额度 |

```java
BodySize body = new BodySize(width, height);
MovementLimits limits = new MovementLimits(stepHeight, jumpHeight, safeDrop, maxGap);
NavProfile profile = new NavProfile(body, limits, Set.of());
SearchBudget budget = new SearchBudget(maxNodes, maxDistance);
NavRequest request = new NavRequest(start, NavGoals.point(target), profile,
        List.of(DangerLayer.INSTANCE), budget);
AStarPlanner planner = new AStarPlanner(BuiltInCapabilities.all());
NavResult result = planner.plan(snapshot, request);
```

能力实现负责移动可行性，必须确定性地返回后继；每条边的基础代价不得小于整数节点与实际脚底端点
两种距离中的较大者。`WALK/STEP_UP/DROP/JUMP_UP/LEAP` 均支持八个水平方向，斜向禁止穿角。
自定义目标的 `lowerBound` 不得高估剩余代价。代价层和自定义能力必须线程安全、只读快照。
同一快照、请求、能力顺序及层返回值产生完全相同的结果；队列用估价、剩余下界、插入序号稳定排序。
`PreferLayer` 打分范围为 `[0, 1]`，1 最偏好，以非负惩罚表达偏好，避免破坏 A* 下界。

地面 `GridPos.y` 标识支撑方块的上一格；`VoxelGeometry.feet(context, position)` 根据碰撞箱返回
实际脚底顶面。土径上脚底可能为 `y - 1/16`，半砖上可能为 `y - 1/2`；`GridPos.center()`
仍返回整数 y，不能用于代替矮方块的实际脚底。自定义能力可用 `VoxelGeometry.canStand/clearLine/clearJump`
检查空间，并给五参数 `MoveEdge` 传入 `MoveEndpoints`。原四参数构造器保留，默认使用格点端点。
`Cell(terrain, hazard)` 也保留，`SOLID` 默认为整格碰撞，其他类型默认为空形状；
精确地形使用 `Cell(terrain, hazard, collisions)` 和 `CollisionBox.floor(height)`。

`SearchBudget.maxNodes` 保留名称，现为合计工作额度：`visitedNodes + ceil(readCells / 32)`。
每个位置仅首次访问源网格，边界外无需读取；缓存同时覆盖内置能力、自定义能力与代价层。
达到额度后返回 `BUDGET_EXHAUSTED`，`NavResult` 保留已消耗的节点与读取计数。
离线完整快照的复制由调用方显式触发，不属于 `AStarPlanner.plan` 的读取预算；Fabric 不再预先复制范围。

## Fabric 托管

包名：`dev.kizuna.navigation.fabric`。公开入口为 `KizunaNav`，不依赖其他 Kizuna 模组。

| 启动期 API | 用途 |
| --- | --- |
| `register(EntityType<?>, ProfileProvider)` | 整类托管登记；只有托管模式，没有兼容模式开关 |
| `addCostLayer(EntityType<?>, CostLayer)` | 类型共享的附加代价，单体接管也会使用 |
| `registerCapability(Identifier, MoveCapability, MoveExecutorFactory)` | 同时登记规则和执行器，ID 必须一致，不得覆盖内置类型 |
| `configure(NavigationSettings)` | 设置近距离请求、全局工作、队列及恢复预算 |
| `settings()` | 读取不可变设置 |

以上登记须在模组初始化期间完成，服务端启动后冻结。重复登记和不匹配 ID 立即报错。
整类接管只在服务端主线程的实体加载事件触发；新建、读档、维度迁移与转换都覆盖。
重复加载检查不更换已有句柄。构造阶段无导航注入；原导航的开门、通过已开门和游泳标志复制到托管导航。
实际规划权限仍由 `NavProfile` 提供，宿主需要显式声明 `OPEN_DOORS/SWIM` 等权限。

| 运行时 API | 用途 |
| --- | --- |
| `takeOver(MobEntity, ProfileProvider) -> NavHandle` | 接管一个实体；已托管时返回现有句柄，不更换 provider |
| `release(MobEntity)` | 取消请求和移动，还原原来的导航对象；未托管时无操作 |
| `of(MobEntity) -> NavHandle` | 获取现有句柄；未托管时抛出异常 |
| `NavHandle.currentProfile()` | 在主线程调用 provider，取得当前有效参数 |
| `requestTo(BlockPos)` | 按实际脚底修正起点；目标允许落在指定格下方或格内碰撞顶面，构造默认预算请求 |
| `navigateTo(BlockPos, double)` | 设置原版速度倍率，构造、提交请求并自动执行成功结果 |
| `planAsync(NavRequest[, NavPriority]) -> NavTicket` | 排队规划，并自动执行成功结果 |
| `setListener(MoveListener)` | 设置段开始前的批准/否决及完成/失败回调 |
| `currentPlan()`、`state()`、`replanCount()` | 查询正在跟随的计划、移动状态和当前请求的恢复次数 |
| `stop()` | 取消排队和移动，清除当前目标 |
| `NavTicket.result()` | `CompletionStage<NavResult>`，只报告规划；异常通过 stage 传播 |
| `NavTicket.cancel()` | 取消尚未完成的规划；已应用的计划使用 `stop()` 停止 |

这些运行时操作和监听/执行器回调均在服务端主线程进行。不要在其他线程读取或修改实体。
`NavTicket.result()` 的完成回调默认也在主线程；自行使用异步回调后，如需操作实体，必须交回服务端执行器。

```java
ProfileProvider profiles = mob -> {
    BodySize body = new BodySize(mob.getWidth(), mob.getHeight());
    MovementLimits limits = new MovementLimits(stepHeight, jumpHeight, safeDrop, maxGap);
    return new NavProfile(body, limits, Set.of());
};

NavHandle navigation = KizunaNav.takeOver(mob, profiles);
navigation.setListener(listener);
NavTicket ticket = navigation.navigateTo(target, speedMultiplier);
```

`ProfileProvider` 只读取实体并返回不可变参数，不得产生结算副作用；宽高必须包住实际实体，
Fabric 当前支持宽度不超过 4 格、高度不超过 8 格。参数上限由 provider 决定，库不进行体力或战斗结算。

Fabric 在真正计算时用实体的实际位置和 provider 当前值替换请求的 `start` 和 `profile`，
保留目标、代价层及预算。有效层顺序为默认 `DangerLayer`、类型登记层、请求层。
默认危险禁止不能用负代价抵消。原始请求的距离预算不得超过配置，工作额度不得超过全局每 tick 预算。
`NavigationSettings.nodesPerTick` 保留名称，但包含首次方块读取的折算开销；默认 4000 单位。
队列先预留请求额度，再按 `NavResult.workUnits()` 结算，首版仍在主线程 tick 中完成计算。
`requestTo` 的目标是包含指定 y 和上一格的窄区域，以接纳地毯等格内顶面；自定义精确目标仍可使用
`NavGoals.point`。实体起点用碰撞箱脚底向上取整得到节点，再由核心校验实际支撑和净空。

## 移动扩展

`MoveListener.beforeMove(MoveContext)` 返回 `ALLOW` 或 `DENY`。宿主可在允许时扣费或触发表现；
返回 `DENY` 不启动执行器，并通过 `failed(..., VETOED)` 通知和重算。
`completed` 在该段确认到达时调用；`failed` 报告卡住或执行失败。显式 `stop/release` 是取消，不伪造失败。

`MoveExecutorFactory` 每段创建独立 `MoveExecutor`，其 `tick()` 返回 `RUNNING/SUCCEEDED/FAILED`；
完成、失败、停止或接管释放后调用 `cancel()` 清理输入。执行器不可直接更改实体坐标来绕过碰撞。
回调异常会记录错误并终止该移动段，不能把异常当作成功。

自定义段也进入只读的原版 `getCurrentPath()` 占位节点列表，只有本库按 `MoveKind` 解释和执行它们。
该 `Path` 仅供观察，不构成原版同步规划兼容支持，不应从外部修改。
