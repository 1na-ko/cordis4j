# Cordis4j 设计契约（Design Contract）

> 状态：**v1.0 冻结**（对应 v0.1.0）。任何语义变更必须经由决策日志（§2）追加新条目并提升版本。
> 语义基线：cordis 论文《A Programming Paradigm for Spatiotemporal Composability》§3–§5（下文引用章节号即论文章节号）；
> 参考实现：cordiverse/cordis 与 @deepseek-ai/cordis@4.0.1（MIT）。
> Cordis4j 是论文语义的 **Java 重想**（inspired-by，非逐行移植）；与上游 TS API 的一切差异在 §5 显式声明。

---

## 1. 目标与范围

### 1.1 目标

在 JVM 上实现"时空可组合性"内核（v0.1.0 垂直切片）：

- **时间维（可逆效应）**：每个上下文变更携带显式逆，运行时按 LIFO 累积并在卸载时整体恢复（论文 §3.1、Algorithm 1）；
- **空间维（反应式协效应）**：服务以类型化键供给/解析，支持限定符（realm 投影）与隔离派生（论文 §3.2、§5.1.2）；
- **统一上下文树**：fork 派生隔离子上下文，dispose 级联恢复（论文 §3.3.1）；
- **组件生命周期**：两态（INACTIVE/ACTIVE）全同步（论文 §4.2 简化；惯性状态机为 P2）。

### 1.2 本轮范围外（P2/P3）

声明式加载器与配置调和（§5.2.1）、HMR（§5.2.2）、惯性异步状态机（§4.3.3）、注解/代理注入（上游 mixin）、
事件过滤器、线程安全、Spring/Quarkus/LangChain4j 集成、字节码级热替换（JVM ClassLoader 方案）。

---

## 2. 决策日志

| # | 决策 | 内容 | 依据/理由 |
|---|---|---|---|
| D1 | 服务获取方式 | 显式 ctx.get(ServiceKey) / find 返回 Optional；注解注入在 P2 | 论文 §6.4：注解+编译期生成是 Proxy 中介的替代路径；P1 保持零依赖 |
| D2 | 插件表示 | @FunctionalInterface Plugin.apply(Context) -> Disposable | 对应 fiber.apply；Java 惯用法 |
| D3 | 事件模型 | 全同步分发；emit 当前上下文先行、沿父链上溯（子→根）；监听器抛异常向上传播、剩余监听器跳过 | 与上游一致；P2 虚拟线程异步 |
| D4 | 命名 | groupId/包名 io.cordis4j；artifactId cordis4j-core；JPMS 模块 io.cordis4j.core | 检索确认无冲突；定稿不改 |
| D5 | 服务键 | ServiceKey<T> = (Class<T> type, String qualifier)；qualifier 为 realm 一维投影；get(Foo.class) 为默认限定符糖 | 预留给 §6.2 同接口多提供者与 loader realm 的扩展点；T9/T10 固定行为 |
| D6 | 异常体系 | CordisException（基类）→ NoSuchServiceException（含键与查找路径）/ InactiveAccessException（P2 声明校验启用）/ DisposeException（聚合清理失败） | 对齐上游 Algorithm 6 两类访问错误；T7 固定聚合语义 |
| D7 | 生命周期 | 两态全同步；内部 Lifecycle 接缝（SimpleLifecycle 实现），P2 换惯性状态机 | 论文 §4.3.3 需异步任务句柄，P2 以虚拟线程重访 |
| D8 | 线程模型 | P1 单线程，契约明文"未同步" | 先正确后并发 |
| D9 | Service.start/stop | 显式标注为**扩展**（非论文语义）：活跃插件域内 provide 时 start()，域撤销时 stop() 逆序 | 不冒充论文语义；论文顺序保证由 T6 覆盖 |
| D10 | 许可证/署名 | Cordis4j 以 MIT 发布；README 致谢 cordis 论文（cordiverse/paper）与参考实现 cordiverse/cordis、@deepseek-ai/cordis（代码仓库均 MIT）；论文仅引述、不断言其许可 | 上游代码全 MIT，无法律障碍 |

---

## 3. API 契约

包 io.cordis4j.core；实现类位于 io.cordis4j.core.internal（模块不导出）。

    public interface Disposable extends AutoCloseable
        语义：效应之逆。dispose() 幂等（重复调用 no-op）；close() 委托 dispose()。
        约定：所有返回 Disposable 的 API，其 dispose 可在任意时刻调用；调用后注册物即被撤销。

    public final class Disposables
        none() -> Disposable：no-op 单例。
        of(Runnable action) -> Disposable：首次 dispose 执行 action（至多一次），后续 no-op。
        composite(Disposable... parts) -> Disposable：按参数顺序依次 dispose；某部分抛异常时收集并继续（语义同 DisposeException）。

    public record ServiceKey<T>(Class<T> type, String qualifier)
        语义：服务键 = 类型 × 限定符（realm 一维投影）。of(type) 等价于 of(type, "")。
        不变式：type 非 null；qualifier 非 null（构造器紧凑构造自动 requireNonNull）。

    public interface Service（扩展，D9）
        default void start() {}
        default void stop() {}
        语义：在活跃插件域内 provide 时立即 start()；域 dispose 时按注册逆序 stop()。仅当对象显式实现本接口时生效。

    @FunctionalInterface public interface Plugin
        Disposable apply(Context ctx);
        语义：apply 在隐式效应域内执行（论文 fiber.apply）；域内一切注册归该插件所有，
              卸载时 LIFO 撤销。apply 返回的 Disposable 并入该域（惯常返回 Disposables.none()）。
              apply 抛异常：已登记效应先 LIFO 撤销，异常再传播（论文 §4.3.4 Failure 简化）。

    public interface Context extends Disposable
        —— 协效应（§5.1.2）——
        <T> T get(ServiceKey<T> key)   // 沿树上溯（realm 判定）；未提供抛 NoSuchServiceException（含路径）
        <T> T get(Class<T> type)       // = get(ServiceKey.of(type))
        <T> Optional<T> find(ServiceKey<T> key)  // 可选查找；never throws
        <T> Optional<T> find(Class<T> type)
        <T> Disposable provide(ServiceKey<T> key, T service)
            // 覆盖式绑定（同上游 set 语义）；返回撤销注册的 Disposable；被覆盖后旧 Disposable 变 no-op
        <T> Disposable provide(T service)  // 键 = 具体类 + 默认限定符
        <T> Context isolate(Class<T> type, String realm)
            // 派生子上下文，仅覆盖该类型键的 realm 映射（§5.1.2 派生语义）；
            // 返回的 Context 即该子上下文（Context 继承 Disposable），dispose 它 = 丢弃子上下文（隐式恢复，无显式逆）；
            // 子上下文同时被登记为父的活动域效应 → 父卸载时级联丢弃
        <T> Disposable intercept(ServiceKey<T> key, Object metadata)
            // @Experimental：P1 仅写入/查询每键拦截元数据表（§5.1.2 @@intercept 的数据结构部分）；
            // 元数据的消费语义（如何调整绑定使用）在 P2 硬化
        <T> Optional<Object> interceptOf(ServiceKey<T> key)
            // 查询拦截元数据：沿树上溯，首中即返；无则 empty

        —— 效应（§5.1.1，Algorithm 1）——
        EffectScope effect()
            // 开启效应分组。惯用法：try (var fx = ctx.effect()) { fx.track(...); ... }
            // close()/dispose() 按 LIFO 执行域内登记的逆；失败聚合为 DisposeException（T7）

        —— 事件（效应实例，D3）——
        <E> Disposable on(Class<E> type, Consumer<E> listener)
        <E> void emit(E event)
            // 同步分发：本上下文监听器 → 父链 → 根（子 emit 触发祖先监听器；祖先 emit 不触子）

        —— 空间（§3.3.1）——
        Context fork()          // 派生子上下文；子 dispose 注册为父活动域效应 → 父卸载自动级联子
        Context root()          // 根上下文

        —— 组合入口（Algorithm 4 的 Java 形态）——
        Disposable plugin(Plugin plugin)
        Disposable plugin(Object... services)   // 便捷：仅 provide 各服务的插件
        Logger logger(String name)             // 最小 Logger + java.util.logging 适配

    public final class Contexts
        static Context create()  // 创建根上下文

    public class CordisException extends RuntimeException          // 体系基类
    public class NoSuchServiceException extends CordisException    // 携带 ServiceKey 与查找路径
    public class InactiveAccessException extends CordisException   // P1 仅定义类型，P2 声明校验时抛出
    public class DisposeException extends CordisException          // suppressed 收集全部清理失败

---

## 4. 与论文/上游的对应表

| Cordis4j | 论文构造 | 上游 TS |
|---|---|---|
| provide / get | set/get（Algorithm 2：k → ρ(k) → σ 两层解析） | ctx.provide(name, value) / ctx.get(name) |
| ServiceKey(type, qualifier) | 键 k 与 realm 符号 ρ(k) 的 P1 投影 | 字符串键 + ctx.isolate(name, realm) |
| effect().track(d) | ctx.effect：逆 prepend 进累积器（LIFO） | ctx.effect(callback) |
| plugin(Plugin) | use/instantiation（Algorithm 4：父效应携带子卸载） | ctx.plugin(plugin) |
| fork() | 上下文树分叉（§3.3.1：子可见父、父不可见子） | ctx.fork() |
| dispose() | 撤回（§4.3.1）+ 累积器恢复 | fiber dispose |
| isolate(type, realm) | §5.1.2 派生子上下文覆盖 realm 表 | ctx.isolate(name, realm) |
| intercept(key, meta) | §5.1.2 @@intercept 数据结构 | ctx.intercept(name, config) |
| on/emit | 事件=效应实例；沿树冒泡 | ctx.on / ctx.emit |

---

## 5. 偏离与扩展声明（与上游 TS 的显式差异）

1. 键制：上游为字符串键 + 每类型 module augmentation；Cordis4j 为 ServiceKey(Class, qualifier)。qualifier 承担 realm 角色，P2 的 loader 多 realm（§5.2.1）在 ServiceKey 上扩展而非换键制。
2. 获取失败：上游 ctx.get(key)（store 查询）从不失败，失败的是 Proxy 属性访问（INACTIVE_ACCESS / UNDECLARED_ACCESS）；Cordis4j get() 抛 NoSuchServiceException、find() 返回 Optional，InactiveAccessException 预留给 P2 声明校验。
3. 生命周期：上游为惯性异步状态机（RELOADING/UNLOADING/FAILED）；P1 为两态同步 SimpleLifecycle，Lifecycle 接缝保证 P2 可替换。
4. 异步：上游效应与迁移均为异步（create_task）；P1 全同步，P2 以虚拟线程重访。
5. Service.start/stop：上游服务为值、生命周期在 fiber 层；Cordis4j 的 Service 钩子为显式扩展（D9），不构成论文语义的一部分。
6. 属性访问：上游 ctx[key] 经 Proxy 中介并强制声明校验（Algorithm 6）；Cordis4j 无动态属性，P2 以 @Inject 注解 + 编译期/代理生成实现同等中介。
7. 事件过滤器（ctx.filter）：P2。
8. Logger/logger(name)：为对齐上游 built-in service 提供的最小化版本（java.util.logging 适配），不引入第三方依赖。

---

## 6. 边界语义（逐条由测试固定）

1. dispose 幂等：重复 dispose no-op。
2. dispose 重入：某逆的执行过程中再次 dispose 同一域/上下文 → 第二次调用 no-op。
3. 已 dispose 上下文：其 get/emit/plugin/fork/provide 抛 IllegalStateException。
4. provide 覆盖：同键重复 provide 覆盖旧绑定；旧 Disposable 变 no-op；被覆盖服务若实现 Service 则其 stop() 在覆盖时按扩展语义执行。
5. null 拒绝：全部公共 API 参数 Objects.requireNonNull。
6. 监听器异常：emit 中某监听器抛异常 → 传播给 emit 调用者，剩余监听器（含祖先链）不再投递。
7. plugin.apply 异常：已登记效应 LIFO 撤销后异常传播。
8. 查找路径：get 沿树上溯；每层先查 realmOverrides[type]：有且 ≠ key.qualifier 时跳过该层（隔离），否则查该层服务库。
9. isolate 派生：子上下文继承父一切，仅覆盖指定 type 的 realm；dispose 子即整体丢弃。
10. fork 级联：子上下文的 dispose 注册为父的活动域效应；父 dispose 时子先于父的早先效应被撤销（LIFO 跨 fiber 树，T6）。
11. 事件冒泡方向：仅子→祖先；同上下文监听器按注册顺序同步投递。
12. 单线程：全部操作非线程安全，跨线程共享上下文属未定义行为（D8）。

---

## 7. 生命周期模型（P1）

- 状态：INACTIVE / ACTIVE（两态）。
- 迁移：域创建 → 执行 apply（=LOADING 的同步化）→ ACTIVE；dispose → 逆序撤销 → INACTIVE。
- 接缝：internal.Lifecycle { void dispose(); }，P1 唯一实现 SimpleLifecycle；
  P2 以惯性状态机实现替换（对应论文 Algorithm 5 的 refresh/reload/unload 及 fiber.inertia）。

---

## 8. 演进策略

- 语义版本化：0.x 期间允许破坏性变更，但必须更新本契约 + 决策日志 + CHANGELOG。
- 稳定锚点：ServiceKey 形态、Disposable/EffectScope 契约、异常体系、fork 级联语义为跨 P2/P3 稳定接口。
- P2 入口（已预留）：声明式 inject + Algorithm 3/5 排空顺序、Lifecycle 惯性实现、注解注入、事件过滤、虚拟线程异步。
- P3 入口：字节码级 HMR（自定义 ClassLoader/ModuleLayer 评估，参考 OSGi/pf4j 先例）。

---

## 9. 参考

- 论文：A Programming Paradigm for Spatiotemporal Composability，https://github.com/cordiverse/paper
- 上游：https://github.com/cordiverse/cordis ；@deepseek-ai/cordis@4.0.1（vendored in deepseek-harness）
- 可行性评估（已归档）：docs/design/cordis4j-feasibility-review.md
- Koishi 可逆插件设计：https://koishi.chat/zh-CN/cookbook/design/disposable.html
