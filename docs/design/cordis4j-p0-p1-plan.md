# Cordis4j 实施计划（P0–P1：核心语义 + 垂直切片）

> **状态：已归档（2026-08-14）**。本文为过程产物（历史计划草稿）；当前生效的设计真相见 [../design-contract.md](../design-contract.md)，决策与 API 以契约为准。
> 状态：草拟（已归档）。本文档只描述计划，不含实现代码。
> 依据：cordis 论文《A Programming Paradigm for Spatiotemporal Composability》（工作区内有中文译本 `cordis-paper/`，下文引用章节号即论文章节号）。

---

## 0. 目标与成功标准

**P0 目标**：产出一份"设计契约"——Java API 草图 + 语义决策清单，把 Cordis4j 与上游 cordis 的关系、范围、命名、语义一次定死。

**P1 目标**：产出一个可运行的垂直切片——`cordis4j-core` 模块跑通"效应追踪 → 服务供给/解析 → 事件 → 插件生命周期 → fork/dispose 隔离"完整链路，并用测试固定语义。

**P1 成功标准（验收门槛）**：
1. 一条端到端演示（demo）代码 ≤ 60 行，展示"装配两个插件 → fork 会话 → 会话内事件与服务可用 → dispose 后插件被卸载、效应被逆序撤销"；
2. 核心语义测试 ≥ 6 条全部通过（见 §4）；
3. 核心模块**零运行时依赖**，仅测试期依赖 JUnit 5；
4. `README.md` 中的 demo 与测试即为全部文档。

---

## 1. 范围界定（做什么 / 不做什么）

### P0–P1 做

| 语义 | 论文依据 | Java 落点 |
|---|---|---|
| 可逆效应（effect tracking） | §3.1、§5.1.1 | `Context.effect(...)`：分组记录逆操作，dispose 按 LIFO 撤销 |
| 协效应解析（服务查找） | §3.2.1、§5.1.2 | `ctx.get(Class<T>)` 沿上下文树上溯，`provide` 注册 |
| 协效应隔离/拦截 | §3.2.3、§5.1.2 | `ctx.isolate(key, realm)`、`ctx.intercept(...)`——P1 仅留接口，语义在 P2 硬化 |
| 组件生命周期（两态模型） | §4.2、§5.1.3 | 插件 apply/start/stop；服务 start/stop；清理函数 LIFO |
| 上下文树（fork/dispose） | §3.3.1、§5.1.4 | `fork()` 子上下文，`dispose()` 整树卸载 |
| 事件 | 论文无专章（属效应的一类实例） | `ctx.on(...)` / `ctx.emit(...)`，注册即产生可撤销效应 |
| 垂直切片 demo + 测试 | — | §4 |

### P1 明确不做（属于 P2+）

- 惯性状态机（RELOADING/UNLOADING/FAILED，§4.3.3）——P1 只实现两态（INACTIVE/ACTIVE）；
- 声明式组件加载器与配置调和（§5.2.1）——但 **P2 验收会把"配置级热重载"作为核心语义的验收标准**（见 §7）；
- 热模块替换（§5.2.2）、字节码级热重载（JVM 类加载器方案在 P3 才评估）；
- 异步迁移语义（§4.3.3 的 inertia）——P1 全同步，P2 用虚拟线程重访；
- 事件过滤器（filter）、Schema 校验、注解驱动的动态代理注入（mixin 的 Java 对应物）。

---

## 2. 技术基线

| 项 | 决策 |
|---|---|
| 语言/运行时 | Java 21（虚拟线程为 P2 异步迁移预留） |
| 构建 | Maven 单模块 `cordis4j-core`，之后按需拆多模块 |
| 依赖 | 运行时零依赖；测试仅 JUnit 5 |
| 包名 | `io.cordis4j.core`（groupId `io.cordis4j`）【待拍板 D4】 |
| 代码风格 | 遵循 Java 习惯（inspired-by cordis，不逐行对齐 TS API） |

---

## 3. P0 设计契约：核心 API 草图

```java
/** 可撤销的注册：等价于论文中"效应的逆"。dispose() 幂等。 */
public interface Disposable extends AutoCloseable {
    void dispose();
    default void close() { dispose(); }
}

/** 服务：协效应的运行时形态。生命周期钩子可选实现。 */
public interface Service {
    default void start() {}
    default void stop() {}
}

/** 插件：组件（component）的运行时形态。apply 期间产生的一切注册
 *  自动归入一个隐式效应；返回的 Disposable 用于额外清理。 */
@FunctionalInterface
public interface Plugin {
    Disposable apply(Context ctx);
}

/** 上下文：效应上下文与协效应上下文的统一类型（论文 §3.3.1）。 */
public interface Context extends Disposable {
    /* ── 协效应操作（§5.1.2）── */
    <T> T get(Class<T> type);                  // 沿树上溯，找不到抛异常
    <T> Optional<T> find(Class<T> type);       // 可选查找
    <T> Disposable provide(T service);         // 注册服务；返回撤销注册
    <T> Disposable isolate(Class<T> key, Object realm); // P1 仅接口
    <T> Disposable intercept(Class<T> key, Object meta); // P1 仅接口

    /* ── 效应操作（§5.1.1）── */
    EffectScope effect();                      // 开启效应分组
    interface EffectScope extends Disposable {
        <D extends Disposable> D track(D d);   // 登记一个逆
    }

    /* ── 事件（效应的一类实例）── */
    <E> Disposable on(Class<E> type, Consumer<E> listener);
    <E> void emit(E event);

    /* ── 空间操作（§3.3.1 上下文树）── */
    Context fork();                            // 隔离子上下文
    Context root();                            // 根上下文

    /* ── 组合入口 ── */
    Disposable plugin(Plugin plugin);          // apply 并托管其效应
    Disposable plugin(Object... services);     // 便捷：仅注册服务的插件
}
```

**与论文语义的对应**：

| Java API | 论文构造 |
|---|---|
| `provide` / `get` | 供给 `provision` 与解析 `resolve`（协效应表 Σ） |
| `effect().track(d)` | `ctx.effect`：把逆 prepend 进累积器 𝜑（LIFO 恢复） |
| `on(...)` 返回 Disposable | 事件注册作为一类效应：卸载时自动反注册 |
| `plugin(Plugin)` | 组件实例化（Algorithm 4）：apply 内所有注册记入该 fiber 的 𝜎 |
| `fork()` | 上下文树分叉；子可见父（沿树上溯），父不可见子 |
| `dispose()` | 组件撤回（§4.3.1）+ 效应恢复（累积器 𝜑 作用于状态 𝛾） |

---

## 4. P1 垂直切片定义

### 4.1 端到端 Demo（`demo/QuickStart.java`，≤60 行）

```java
var root = Contexts.create();                        // 根上下文
root.plugin(new TimerPlugin());                      // 根级插件：提供 now() 服务
var session = root.fork();                           // 会话隔离域
session.plugin(ctx -> {                              // 会话级插件：问候逻辑
    ctx.on(Message.class, m ->
        ctx.emit(new Reply(m.from(), "hello, " + m.text())));
    return Disposables.none();
});
session.on(Reply.class, r -> System.out.println(r)); // 会话级监听
session.emit(new Message("alice", "hi"));            // → 打印 hello, hi
session.dispose();                                   // 会话内插件/监听全部卸载
root.emit(new Message("bob", "hi"));                 // → 无输出（会话监听已卸载）
root.get(TimerService.class).now();                  // 父级服务不受影响
```

### 4.2 核心语义测试清单（`src/test/java/...`，P1 验收 = 6/6 通过）

| # | 测试 | 断言的语义 | 论文依据 |
|---|---|---|---|
| T1 | EffectLifo | `effect()` 内注册 a、b、c，dispose 后按 c→b→a 逆序撤销；重复 dispose 幂等 | §3.1.2 累积器 𝜑 的 LIFO |
| T2 | ForkIsolation | 子上下文可见父服务；父不可见子的注册；子 dispose 后父不受影响 | §3.3.1 上下文树 |
| T3 | EventUnregister | `on` 返回的 Disposable 撤销后监听器不再被调用；emit 同步分发 | 效应实例 |
| T4 | PluginUnload | 插件 apply 内注册的服务/事件/清理函数，在所属 fiber dispose 时**全部**逆序撤销 | §4.2 两态模型 + Algorithm 4 |
| T5 | DisposeResilience | 某清理函数抛异常：其余清理仍执行，异常被收集上报（不吞不炸） | §4.3.4 Failure 的简化版 |
| T6 | LifecycleOrder | 服务 start 按注册顺序、stop 按逆序；被禁用前 start 过的服务保证收到 stop | §5.1.3 |

### 4.3 内部实现要点（P1 范围内）

- `Context` 唯一实现类约 300–500 行：一个 `Deque<Disposable>` 累积器 + 一个服务注册表（`IdentityHashMap<Class<?>, Object>`）+ 一个父指针；
- 不做线程安全优化（P1 单线程语义）；文档声明"未同步"；
- 异常策略：dispose 中单个清理失败 → 记入 `List<Throwable>`，继续清理，最后以 `DisposeException` 聚合抛出（T5 断言此行为）；
- `get()` 未找到抛 `NoSuchServiceException`（明确异常类型，附查找路径）。

---

## 5. 里程碑与排期（单人 + agent 辅助）

| 里程碑 | 交付物 | 估计 |
|---|---|---|
| M0 计划核对（当前） | 本文档经用户确认/修订 | 1 次对话 |
| M1 契约冻结 | 上述 API 定稿进 README | 0.5 天 |
| M2 垂直切片 | 核心实现 + demo + T1–T6 全绿 | 1–2 天 |
| M3 公开 | push GitHub + JitPack 可拉取 | 0.5 天 |
| M4 P2 进入 | 配置级热重载验收 + 惯性状态机 | 另行计划 |

---

## 6. 风险与开放问题

### 需你拍板的决策点

- **D1 服务注入方式**：P1 只做显式 `ctx.get(Foo.class)` + `Optional`；注解/动态代理字段注入（对应 TS 的 mixin）留到 P2+。是否认可？
- **D2 插件表示**：函数式接口 `Plugin`（apply 返回 Disposable）而非抽象类。认可？
- **D3 事件模型**：P1 全同步分发（emit 即执行），事件沿上下文树上溯分发（与 cordis 一致：`session.emit` 会触发根级监听器）；P2 以虚拟线程提供异步分发。认可？
- **D4 坐标命名**：`io.cordis4j` 还是别的 groupId/包名？（影响未来 Maven Central 发布，改起来有成本）

### 已识别的风险

- R1 **语义漂移**：上游 cordis 仍在快速迭代（DSH 内核用 `@deepseek-ai/cordis` v4）。对策：以论文 §3–§5 的正式语义为唯一对齐目标，不对齐具体 TS API 形态；
- R2 **两态模型不足以支撑热重载验收**：P2 若发现两态不够，需补惯性状态机。对策：P1 把状态机抽成接口 `Lifecycle`，实现可替换；
- R3 **零依赖 vs 便利性**：事件、DI 均自研可能低估工作量。对策：P1 范围已最小化（见 §1），每个子系统 ≤ 一个类；
- R4 **demo 场景过于玩具**：对策：P2 的 demo 用 Langchain4j 集成（tool 注册/卸载 = 会话中动态装卸能力），那是真正的杀手级演示。

---

## 7. 与"热重载"的关系（回应此前讨论的结论）

热重载不是 P0–P1 的目标，但 P1 的 T4/T5 恰好就是热重载的内核：**卸载干净（LIFO 撤销）+ 失败可恢复**。P2 的第一项验收即"改动组合描述 → 旧插件逆序卸载 → 新插件在新 fork 重挂载，全程无状态泄漏"，届时热重载只是给 `dispose()` + 重新 `plugin()` 套上一个文件监听器。核心先行，热重载后置，但作为核心语义的验收标准写进 P2——这是单人开发的正确顺序。

---

## 附录 A：与上游 cordis 核心概念的对应速查

| 上游概念（TS） | Cordis4j（Java） |
|---|---|
| `ctx.plugin(plugin)` | `ctx.plugin(Plugin)` |
| `ctx.provide(name, value)` | `ctx.provide(instance)`（以 Class 为键） |
| `ctx.get(name)` | `ctx.get(Class<T>)` |
| `ctx.on(event, cb)` / `ctx.emit(event)` | `ctx.on(Class<E>, Consumer<E>)` / `ctx.emit(event)` |
| `ctx.effect(callback)` | `EffectScope.track(Disposable)` |
| `ctx.fork()` | `ctx.fork()` |
| `ctx.scope.update / dispose` | `ctx.dispose()` |
| Schema / 声明式配置 | P2+ |
| HMR（loader 插件） | P2 验收标准 / P3 模块 |