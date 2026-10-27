# 开发与验收

## 固定环境

| 项目 | 版本 |
| --- | --- |
| Java 编译、GameTest 运行 | 17 |
| Minecraft | 1.20.1 |
| Yarn | 1.20.1+build.10 |
| Fabric Loader | 0.16.10 |
| Fabric API | 0.92.3+1.20.1 |
| Gradle wrapper | 8.8，保留官方 SHA-256 校验 |
| Fabric Loom | 1.6-SNAPSHOT（本地解析为 1.6.12） |
| 模组 ID | `kizuna_navigation` |
| 许可 | PolyForm Noncommercial 1.0.0 |

wrapper 和构建布局参照 `Kizuna-Meridian-Vanilla-Damage-Addon`，没有组合构建或该仓库的运行依赖。
发布 Fabric JAR 内嵌本库 core JAR，游戏依赖只有 Fabric API/Loader 和 Minecraft。

## 构建与测试

```sh
./gradlew :core:test
./gradlew :fabric:runGametest
./gradlew build
git diff --check
```

Gradle 使用 Java toolchain 编译 Java 17 字节码；所有 `JavaExec`（包括 GameTest）强制 Java 17。
首次构建需要下载 Gradle、映射和游戏开发依赖。core 的源代码和 JUnit 运行本身不加载游戏。
GameTest 自动启动无客户端服务端，不需要手工启动 Minecraft。

core 报告：`core/build/reports/tests/test/index.html`。
GameTest XML：`fabric/build/gametest/build/gametest-results.xml`。
安装包：`fabric/build/libs/kizuna-pathfinding-framework-fabric-0.1.0-SNAPSHOT.jar`。
只安装普通 JAR，不安装 `-sources.jar` 或开发产物。

新仓库在首次暂存前，`git diff --check` 不包含未跟踪文件；交付还须检查所有文本文件的行尾空白、
Java 行宽和 Git 忽略项，不能仅凭空 diff 宣称源码已验证。

## Yarn 核对证据

实现前通过指定 Yarn 版本的命名 Minecraft JAR，使用 `javap -p` / `javap -p -c` 核对以下签名和调用：

| 实际类/成员 | 实现结论 |
| --- | --- |
| `MobEntity.createNavigation(World)` | `protected`，构造期间虚调用后写入 `navigation` |
| `MobEntity.navigation` | `protected EntityNavigation`，非 `final`，可通过 accessor 写入 |
| `MobEntity.<init>` | 在 `initGoals()` 前构造导航；此时子类尚未完成构造，不注入接管逻辑 |
| `LandPathNodeMaker.getStart()` | 着地时参考实际 y 而非直接使用 `getBlockPos()`；本库按实际脚底修正节点 |
| `LandPathNodeMaker.getFeetY(BlockView,BlockPos)` | 使用下方方块碰撞顶面；本库用独立 Java 碰撞箱计算实际脚底 |
| `MobNavigation.setCanPathThroughDoors(boolean)` | 实际委派到 `PathNodeMaker.setCanOpenDoors`，不能误用名称含糊的 `method_35140` |
| `EntityNavigation.getNodeMaker()` | 复制原导航的 `canOpenDoors/canEnterOpenDoors/canSwim`，保留子类构造配置 |
| `MobNavigation` | 继承 `EntityNavigation`；`createPathNodeNavigator(int)` 创建 `LandPathNodeMaker` 与 `PathNodeNavigator` |
| `EntityNavigation.findPathTo(double,double,double,int)` | `final`，委派到 `findPathTo(BlockPos,int)`，由子类阻断 |
| `findPathTo(BlockPos,int)` / `findPathTo(Entity,int)` | `MobNavigation` 可覆写，托管实现直接返回 `null` |
| 其余集合、流和范围入口 | 最终委派到 `findPathToAny(Set<BlockPos>,int,boolean,int,float)`；统一返回 `null` |
| `MobEntity.tickNewAi()` | 每 tick 调用 `navigation.tick()`，随后调用移动/跳跃控制器；不添加第二套重复 tick |
| `Path(List<PathNode>,BlockPos,boolean)` | 用于托管计划的只读占位路径，节点索引与已完成段同步 |
| `TestContext.addFinalTask` | 指定第 0 tick 成功，不适合等待移动完成 |
| `TestContext.addInstantFinalTask` | 等待断言成功，用于异步规划和实际到点验收 |

只有 `MobNavigationAccessor` 留在生产 mixin 配置中。整类登记通过服务端主线程的
`ServerEntityEvents.ENTITY_LOAD` 接管已完成构造且进入跟踪的实体，卸载事件释放。
构造线程不会替换导航，也不会读取注册表；重复加载检查幂等。单体接管复用同一 accessor。
原版具体实现的映射可在 [Yarn 1.20.1 文档](https://maven.fabricmc.net/docs/yarn-1.20.1+build.10/) 查阅。

## 覆盖范围

`AStarPlannerTest` 与 `NavigationGameTest` 同时覆盖已达成的半径、区域、远离目标空路径，
以及表示点远而最近满足点在范围内的请求。`cancellationCallbackKeepsEveryAcceptedTicketTerminal`
验证同步取消回调提交新请求后，每张已接纳票据均结束；另覆盖停止重入和释放时拒绝新提交。
`fullQueueAllowsReplacingOwnPendingRequest` 验证满队列下自身替换仍成功且旧票据取消；
`fullQueueRejectsAnotherEntity` 验证其他实体仍被容量限制拒绝，原请求继续有效。
core 另验证自定义目标的高代价下界不会被误用为几何距离剪枝。

- core：一格台阶、两格跳高与头顶阻挡、三格空隙及能力不足、安全下落边界、全部危险类型绕行、
  下落途中危险、重复输入完全一致、节点预算和范围、实体宽度、自定义能力和被否决边、非法代价、
  不可变快照、门/水/攀爬权限、全部声明高度的弹道，以及下表中的多格跳跃、斜向和按需读取用例。
- GameTest：单体托管掠夺者跨沟到点、整类托管卫道士跨沟、原版全部寻路入口返回空、
  未登记牛的路径与原版一致且实际移动、否决跨越后走桥、卡住自定义段退避后恢复、请求取消与导航还原、
  两格跳高的真实碰撞与落地、段开始回调中释放、移动参数改变时重算；新增土径、耕地、地毯、
  半砖台阶、灵魂沙、雪层和土径与整格双向交接，以及真实跨沟加两格跳高、斜向跨沟、
  后台线程构造、实体区块数据卸载重载、村民开门标志及真实开门、转换和维度迁移。

测试宿主、假执行器和测试登记只进入 `gametest` source set，不进入发布包。
实体区块重载测试使用真实 `ServerEntityManager` 和区块实体存储：切换为 `HIDDEN`、`flush()` 保存卸载，
恢复 `TICKING` 后验证反序列化的新实例重新被托管；测试 accessor 仅存在于 `gametest`。
维度测试为目标区块启用跟踪，以真实迁移流程验证加载接管，不依赖未跟踪实体的构造时状态。
性能压力、村民完整 Brain 兼容、远距离和客户端渲染没有在此处宣称通过。

## 多格跳跃检查清单

以下均在 `AStarPlannerTest` 中实际执行，不只检查弹道参数：

| 用例 | 断言 |
| --- | --- |
| 平沟宽度 `1..maxGap` | 每种宽度成功，`maxGap + 1` 无路 |
| 跨沟到高 1 格和高 `JUMP_HEIGHT=2` 的平台 | 成功并生成单个 `LEAP`；再高一格无路 |
| 跨沟到低 `safeDrop=3` 的平台 | 成功；再低一格无路 |
| 八方向跨沟 | 正交与斜向均测试平、高、低落点，代价不小于欧氏距离 |
| 顶棚和中途障碍 | 覆盖整个弹道的顶棚及中途障碍分别拒绝 |
| 土径起跳、半砖落点 | 保存实际脚底高度；低净空拒绝 |
| 矮方块造成的额外高差 | 整数节点差合规、实际跳高或落差超限时仍拒绝 |

另有八方向走、台阶、跳高和下落、空旷对角线连续斜走、穿角拒绝、两端可站但途中宽身体受阻、
低顶棚下半格台阶可行性用例。`NavigationTerrainGameTest.realLeapCrossesGapAndRisesTwoBlocks`
在真实世界中完成三格沟加两格跳高，并核对实际执行段、落地位置、高度和着地状态。

## 按需读取与调度计量

`WorldSnapshots.capture` 只建立范围与主线程读取入口。A* 的单次规划缓存覆盖所有能力和代价层，
相同 `GridPos` 只读取一次；下一次规划重新读取，不跨请求保留陈旧世界状态。
全局与请求额度均按 `visitedNodes + ceil(readCells / 32)` 计量，读取前拦截超额；
`SearchBudget.maxNodes`、`NavigationSettings.nodesPerTick` 为兼容保留原名。
自定义能力大量读取也会返回 `BUDGET_EXHAUSTED`，不会绕过额度。

同一 `runtimeManagedPillagerCrossesThreeBlockGap` 场景，以原实现临时计数跑过基线，再由新结果计数：

| 指标 | 修改前 | 修改后 |
| --- | --- | --- |
| 单次规划读取格数 | 188,538 | 355 |
| 扩展节点 | 未在基线单独记录 | 3 |
| 工作单位 | 只计算节点，读取不计费 | 15（3 个节点 + 12 组读取） |

读取格数减少约 99.81%。原快照受测试世界底部裁剪，体积为 `67 × 67 × 42`；
不应把未裁剪的约 30 万格当作此场景实测基线。计数只用于说明这条短路径，不能推导所有地形的性能。

## 本地验收结果（2026-10-25）

`./gradlew :core:test :fabric:runGametest build --console=plain` 通过：

| 检查 | 结果 |
| --- | --- |
| core JUnit | 29 项，0 失败，0 跳过 |
| Fabric GameTest | 25 项必需测试全部通过 |
| 多模块构建、重映射与源码包 | 通过 |
| Java 版本 | 游戏日志为 Java 17，产物 class major version 为 61 |
| 发布包隔离 | 仅内嵌 core，未包含测试类或测试模组 |

交付保留本地未提交状态；没有远端仓库、提交或推送。
