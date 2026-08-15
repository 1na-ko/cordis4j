# Cordis4j 设计契约（Design Contract）

> 本文档是英文规范本 [../design-contract.md](../design-contract.md) 的中文译本（规范本语言：英文）。
> 如有歧义，以英文版为准。最近同步：2026-08-15（v2.1，追加 D21）。
> 状态：**v2.3 冻结**（对应 v0.2.1）。任何语义变更必须经由决策日志（§2）追加新条目并提升版本。
> v2.3 在 v2.2（D22）基础上追加 D23（intercept 链消费）。
> 语义基线：cordis 论文《A Programming Paradigm for Spatiotemporal Composability》§3–§5（下文引用章节号即论文章节号）；
> 参考实现：cordiverse/cordis 与 @deepseek-ai/cordis@4.0.1（MIT）。
> Cordis4j 是论文语义的 **Java 重想**（inspired-by，非逐行移植）；与上游 TS API 的一切差异在 §5 显式声明。

---

## 1. 目标与范围

### 1.1 目标

在 JVM 上交付时空可组合性内核（v0.2.0，论文 §3–§5 核心库层全覆盖）：

- **时间维（可逆效应）**：每次上下文变更携带显式逆；运行时按 LIFO 累积、卸载时整体恢复
  （论文 §3.1，Algorithm 1）；
- **空间维（反应式协效应）**：类型化键 + realm 限定符与隔离派生（§5.1.2），以及反应式依赖声明——
  满足即激活、撤退即排空、回归即重激活（Algorithm 3，Theorem 63）；
- **统一上下文树**：`fork()` 派生隔离子上下文，`dispose()` 级联恢复（§3.3.1）；
- **组件生命周期**：带惯性的四态 fiber 状态机——卸载等待飞行中激活落地、dependents 先于
  providers 撤销（§4.2–4.3，Algorithm 4/5），含失败路由与环守卫；
- **异步**：虚拟线程激活、可逆 spawn 任务、guard 协议（§4.3.2–4.3.3）；
- **声明式加载器**：id 键控配置 diff 与事务性调和（§5.2.1，Algorithm 10，配置级）。

### 1.2 范围外（P3）

字节码级热模块替换已落地为独立模块（cordis4j-hmr，docs/design/hmr-evaluation.md 的阶段 1：
零依赖自定义 ClassLoader 引擎，jar 粒度模块分类与事务性重载）；范围外剩余：ModuleLayer 变体
（阶段 2）与文件粒度 import 图分类。Quarkus 集成已评估并推迟
（docs/design/quarkus-evaluation.md 建议在具体部署需要时实现为纯 CDI 模块）。注入的编译期注解
处理已落地为独立模块（cordis4j-inject-processor，T28）；LangChain4j 工具桥接与 Spring 集成已
落地为独立模块（cordis4j-langchain4j、cordis4j-spring），处于本核心契约之外。

---

## 2. 决策日志

| # | 决策 | 内容 | 理由 |
|---|---|---|---|
| D1 | 服务访问 | 显式 ctx.get(ServiceKey) / find 返回 Optional；注解注入在 P3 | 论文 §6.4：注解 + 编译期生成是代理中介的认可替代；核心保持零依赖 |
| D2 | 插件形态 | @FunctionalInterface Plugin.apply(Context) -> Disposable；apply 内的注册属于隐式效应域 | 对应论文 fiber.apply；Java 惯用形态 |
| D3 | 事件模型 | 全同步分发；emit 先跑当前上下文，再沿父链到根（子→根）；监听器抛异常则传播且剩余跳过（D16 扩展分发语义） | 与上游一致；虚拟线程异步在 D15 |
| D4 | 命名 | groupId/package io.cordis4j；artifactId cordis4j-core；JPMS 模块 io.cordis4j.core | 检索确认无冲突；冻结 |
| D5 | 服务键 | ServiceKey<T> = (Class<T> type, String qualifier)；qualifier 是 realm 的一维投影；get(Foo.class) 是默认限定符糖 | 为论文 §6.2 多提供者与 loader realm 预留扩展点 |
| D6 | 异常体系 | CordisException（基类）→ NoSuchServiceException（含键+查找路径）/ InactiveAccessException（声明校验，D13）/ DisposeException（suppressed 聚合）/ SupplyConflictException / CyclicDependencyException / DivertedException | 对齐上游 Algorithm 6 的两类访问失败与其余守卫信号 |
| D7 | 生命周期 | 四态 fiber 状态机（INACTIVE/LOADING/ACTIVE/UNLOADING）；惯性表现为卸载等待落地（§7） | 论文 §4.2/§4.3.3 |
| D8 | 线程（已被 D19 细化） | 核心同步起步；当前并发模型见 D19 | 先正确后并发 |
| D9 | Service.start/stop | 显式标注为**扩展**（非论文语义）：活跃插件域内 provide 时 start()；域撤销时按提供逆序 stop() | 论文式的顺序由 T6/T12 覆盖 |
| D10 | 许可与署名 | Cordis4j 为 MIT；README 致谢论文与参考实现 | 上游代码全部 MIT，无法律障碍 |
| D11 | 反应式协效应 | ctx.inject(deps, effect) 声明 fiber（Algorithm 3）：全部依赖可解析时激活、被依赖绑定撤销时反应式卸载、可重激活；回调返回的 Disposable 并入 fiber 域（最先撤销） | 实现论文满足/通知/刷新；效应函数形态与 Plugin 对齐 |
| D12 | 供给唯一性 | 两个不同活跃 fiber 不得供给同一 store 键（SupplyConflictException）；ambient 供给自由覆盖（管理员语义） | 论文 §4.2 供给集不相交，Java 侧快速失败 |
| D13 | 声明中介 | 声明式 fiber 运行期间，get/find 只解析其声明的键与自供的键（InactiveAccessException，Algorithm 6）；普通插件不受限 | 上游 Proxy 中介访问校验的 Java 形态 |
| D14 | 失败路由 | inject 激活失败回滚部分域、记录并记日志、永不重试、不传播（§4.3.4）；plugin() 失败保持传播（条款 6.7） | 论文失败语义的兄弟隔离 |
| D15 | 异步 | pluginAsync 在虚拟线程上运行效应函数并等待落地（惯性）；spawn 运行长任务，句柄中断并 join（启动任务是可逆效应）；currentFiber() 暴露 guard（isDiverted/checkDiverted） | §4.3.2–4.3.3 的 Java 惯用形态；guard = retired 或 非(LOADING/ACTIVE) 或 声明不满足 |
| D16 | 事件分发 | 父类型监听器接收子类型事件（isInstance）；可选每监听器过滤器；同上下文内严格按注册顺序（更新 D3） | Java 类层级取代上游字符串键 |
| D17 | 拦截元数据 | 实现 InterceptMetadata 的元数据沿链从根向查询点合并，近端优先（论文右偏幺半群）；其他类型保持 nearest-wins | @@intercept 槽位的消费语义 |
| D18 | 声明式加载器 | LoaderConfig/ComponentEntry 按 id 键控 diff；组件实例即版本（换实例即重载）；调和事务性（失败恢复上一配置）；dispose 按装载逆序 | 论文 §5.2.1 / Algorithm 10 的配置级形态；record 相等性是 Java 原生配置 diff |
| D19 | 线程 | 注册表状态由内部锁保护，获取方向单一（fiber 注册表 → 各上下文 store → 域）；用户代码在锁外执行；provide 触发的反应式通知在该 provide 的监视器释放后运行 | 无锁环；长激活与 teardown（可能 join 任务）从不持有注册表监视器 |
| D20 | 撤退顺序 | 卸载 fiber 先撤出其供给的全部键（排空全部 dependents，含仍在 LOADING 者——惯性的链式卸载），再 LIFO 撤销自身效应；被排空的 dependent 在其 teardown 中仍可解析该依赖 | 论文 L-Leave/L-Unload 与 Theorem 63 的精确实现 |
| D21 | 注解式注入 | 实例的 @Inject(qualifier) 字段由 Injects.injectFields(ctx, instance) 装配为一个 D11 声明：激活时以快照填充字段，撤退/退役时清空，再满足时重填；static/final/原始类型字段装配时立即失败；无注解字段为 no-op | 论文 §6.4：语言缺乏透明拦截原语时，认可注解 + 运行时反射中介依赖访问；核心保持零依赖（仅 JDK 反射） |
| D22 | 事件模式 | on(type, listener, prepend) 插入本 context 既有监听之前；once 仅触发一次后自注销（过滤器生效、手动注销仍可）；第二个函数形监听表（fold）支撑 bail——第一个非 null 结果短路分发（含祖先）——与 waterfall——非 null 结果折叠为下一输入，null 保持累加值；emit 保持 consumer 表路径；冒泡方向保持子→根 | 上游 DispatchMode 的 bail/waterfall 与 prepend 选项及 once 在同步核心的类型化形态（上游对齐基准 docs/design/upstream-parity.md）；parallel/serial 属异步分发，不在范围内 |
| D23 | Intercept 消费 | Context.intercepts(key) 沿树收集绑定元数据，根在前、最近在后（原始链，不合并）；interceptOf 保持该链上的 nearer-wins 幺半群；调用者以任意策略合并列表 | 上游 Service.resolveConfig 的 Java 形态——链收集即消费语义，合并策略留在调用方（上游对齐基准） |

---

## 3. API 契约

包 io.cordis4j.core；实现类位于 io.cordis4j.core.internal（模块不导出）。

    public interface Disposable extends AutoCloseable
        语义：效应之逆。dispose() 幂等；close() 委托 dispose()。
        契约：任何返回 Disposable 的 API 可在任意时刻 dispose；dispose 即撤销该注册。

    public final class Disposables
        none()：共享空操作单例。
        of(Runnable)：首次 dispose 至多执行一次。
        composite(Disposable...)：按参数序执行；失败收集为 DisposeException（suppressed）。

    public record ServiceKey<T>(Class<T> type, String qualifier)
        语义：服务键 = 类型 × 限定符（realm 投影）。of(type) 即 of(type, "")。组件永不为 null。

    public interface Service（扩展，D9）
        default void start() {} / default void stop() {}
        语义：活跃插件域内 provide 立即 start()；域撤销按提供逆序 stop()。仅显式实现时生效。

    @FunctionalInterface public interface Plugin
        Disposable apply(Context ctx);
        语义：apply 在隐式效应域内运行（论文 fiber.apply）；期间一切注册属于该插件，卸载时 LIFO 撤销。
        apply 抛异常时先回滚已注册效应（§4.3.4）再传播，回滚失败挂为 suppressed。

    @FunctionalInterface public interface AsyncPlugin
        Disposable apply(Context ctx) throws Exception;
        语义：同 Plugin，但运行在虚拟线程上（D15），允许阻塞与受检异常。

    public interface Context extends Disposable
        -- 协效应（§5.1.2）--
        <T> T get(ServiceKey<T> key)            // 沿树解析（realm 感知）；失败抛 NoSuchServiceException（含路径）
        <T> T get(Class<T> type)                // = get(ServiceKey.of(type))
        <T> Optional<T> find(ServiceKey<T> key) // 可选查找；不抛
        <T> Optional<T> find(Class<T> type)
        <T> Disposable provide(ServiceKey<T> key, T service)
            // 覆盖语义：返回移除句柄；被覆盖的旧句柄变 no-op；key/服务类型不匹配立即失败（T23）
        <T> Disposable provide(T service)       // 键 = 具体类 + 默认限定符
        <T> Context isolate(Class<T> type, String realm)
            // 派生子上下文，仅重定向该类型的 realm 映射（§5.1.2 派生语义）；
            // 返回的 Context 即子上下文（Context extends Disposable）——dispose 即整体丢弃
        <T> Disposable intercept(ServiceKey<T> key, Object metadata)
        <T> Optional<Object> interceptOf(ServiceKey<T> key)   // 沿链解析，见 D17
        <T> List<Object> intercepts(ServiceKey<T> key)
            // 原始链：根在前、最近在后；调用方以任意策略合并（D23）

        -- 效应（§5.1.1，Algorithm 1）--
        EffectScope effect()
            // 打开效应组。惯用法：try (var fx = ctx.effect()) { fx.track(...); }
            // 关闭/撤销按 LIFO；失败聚合为 DisposeException（T7）

        -- 事件（注册即效应；D3/D16/D22）--
        <E> Disposable on(Class<E> type, Consumer<E> listener)
        <E> Disposable on(Class<E> type, Predicate<E> filter, Consumer<E> listener)
        <E> Disposable on(Class<E> type, Consumer<E> listener, boolean prepend)
        <E> Disposable once(Class<E> type, Consumer<E> listener)          // 触发一次后自注销
        <E> Disposable once(Class<E> type, Predicate<E> filter, Consumer<E> listener)
        <E> Disposable fold(Class<E> type, Function<E, E> listener)       // bail/waterfall 表
        <E> void emit(E event)
        <E> Optional<E> bail(E event)        // 第一个非 null 结果短路（含祖先）
        <E> E waterfall(E event)             // 非 null 结果折叠；无人贡献时原样返回事件
            // 同步：先本上下文监听器，再沿父链到根（子 emit 触达祖先；祖先 emit 不触达子）

        -- 空间（§3.3.1）--
        Context fork()   // 派生子上下文；子的 dispose 是活跃域的效应
        Context root()

        -- 组合入口（Algorithm 4 的 Java 形态）--
        Disposable plugin(Plugin plugin)
        Disposable plugin(Object... services)   // 便捷：只提供服务的插件
        Disposable pluginAsync(AsyncPlugin)     // 虚拟线程；等待激活落地（D15）
        Disposable spawn(Runnable task)         // 可逆任务：句柄中断并 join
        Optional<FiberHandle> currentFiber()    // guard：isDiverted / checkDiverted
        Logger logger(String name)

        -- 反应式协效应（D11，Algorithm 3）--
        Disposable inject(Set<ServiceKey<?>> deps, Function<Context, Disposable> onSatisfied)
        <T> Disposable inject(ServiceKey<T> dep, BiFunction<Context, T, Disposable> onSatisfied)
        <T> Disposable inject(Class<T> dep, BiFunction<Context, T, Disposable> onSatisfied)
        <T1,T2> Disposable inject(ServiceKey<T1>, ServiceKey<T2>,
                                  TriFunction<Context, T1, T2, Disposable> onSatisfied)
            // 满足即激活；撤销即反应式卸载（先排空）；未退役未失败时可重激活；
            // 激活失败路由至卸载

        -- 注解式注入（D21，§6.4）--
        @interface Inject                                // FIELD；String qualifier() 默认 ""
        public final class Injects
            static Disposable injectFields(Context ctx, Object instance)
            // 扫描类层级（至 Object 为止）的 @Inject 字段装配为一个声明；
            // 以激活时刻快照填充，撤退/退役清空，再满足重填；
            // static/final/原始类型字段立即失败（IllegalArgumentException）；无注解字段 -> Disposables.none()
            interface FieldTarget                         // §6.4 的访问器形态
                ServiceKey<?> key()                      // 字段依赖键
                void set(Object value)                   // 写入绑定；null 清空
            static Disposable injectFields(Context ctx, List<FieldTarget> targets)
            // 显式访问器之上的单一声明：运行时形态包装反射目标，编译期处理器
            // （cordis4j-inject-processor）生成直接赋值

    public final class Contexts
        static Context create()  // 创建根上下文

    -- 声明式加载器（D18，§5.2.1 / Algorithm 10）--
    record ComponentEntry(String id, Plugin component)   // 实例身份即版本
    record LoaderConfig(List<ComponentEntry> entries)    // id 唯一
    final class Loader implements Disposable             // id 键控 diff，事务性调和

    public class SupplyConflictException extends CordisException   // D12
    public class CyclicDependencyException extends CordisException // 环守卫（Progress 定理）
    public class DivertedException extends CordisException         // guard 信号（D15）
    public interface InterceptMetadata { InterceptMetadata merge(InterceptMetadata nearer); } // D17

    public class CordisException extends RuntimeException          // 基类
    public class NoSuchServiceException extends CordisException    // 携带 ServiceKey + 查找路径
    public class InactiveAccessException extends CordisException   // 声明校验（Algorithm 6）
    public class DisposeException extends CordisException          // suppressed = 全部清理失败

---

## 4. 与论文/上游的对应表

| Cordis4j | 论文构造 | 上游 TS |
|---|---|---|
| provide / get | set/get（Algorithm 2：两层解析 k → rho(k) → sigma） | ctx.provide(name, value) / ctx.get(name) |
| ServiceKey(type, qualifier) | 键 k 与 realm 符号 rho(k) 的投影 | 字符串键 + ctx.isolate(name, realm) |
| effect().track(d) | ctx.effect：逆前置于累积器（LIFO） | ctx.effect(callback) |
| plugin(Plugin) / pluginAsync | use/实例化（Algorithm 4：父效应携带子卸载） | ctx.plugin(plugin) |
| inject(...) | 依赖声明 d + satisfaction/notify/refresh（Algorithm 3） | ctx.inject(deps, callback) |
| withdraw（内置） | L-Leave/L-Unload 排空（Theorem 63） | fiber 退休驱动 |
| spawn / currentFiber | create_task / guard（§4.3.2–4.3.3） | fiber.inertia |
| fork() | 上下文树派生（§3.3.1：子见父，反向不可） | ctx.fork() |
| dispose() | 撤退（§4.3.1）+ 累积器恢复 | fiber dispose |
| isolate(type, realm) | §5.1.2 派生子上下文重写 realm 表 | ctx.isolate(name, realm) |
| intercept(key, meta) + InterceptMetadata | §5.1.2 @@intercept 数据结构 + 元数据幺半群 | ctx.intercept(name, config) |
| on / emit | 事件即效应；树形上浮 | ctx.on / ctx.emit |
| Loader.reconcile | §5.2.1 声明式调和（Algorithm 10，配置级） | loader 插件 |

---

## 5. 偏离与扩展声明（与上游 TS 的显式差异）

1. 键制：上游字符串键 + module augmentation；Cordis4j 用 ServiceKey(Class, qualifier)。qualifier
   扮演 realm；loader 多 realm 支持扩展 ServiceKey 而非替换键制。
2. 查找失败：上游 ctx.get(key)（store 查询）从不失败——失败的是 Proxy 属性访问
   （INACTIVE_ACCESS / UNDECLARED_ACCESS）；Cordis4j get() 抛 NoSuchServiceException、find() 返回
   Optional，InactiveAccessException 承载 Algorithm 6 的声明校验（D13）。
3. 生命周期：同步内核驱动四态 fiber 状态机（INACTIVE / LOADING / ACTIVE / UNLOADING，§4.2）；
   惯性表现为卸载等待落地，含仍在 LOADING 的 dependents 的链式卸载（D20）。
4. 异步：上游效应与迁移是异步的（create_task）；Cordis4j 同时提供同步内核与虚拟线程形态
   （pluginAsync / spawn，D15）。Algorithm 1 的效应迭代器以 guard 协议
   （currentFiber / isDiverted）呈现，而非语言级生成器。
5. Service.start/stop：上游服务即值、生命周期在 fiber 层；Cordis4j 的 Service 钩子是显式扩展
   （D9），非论文语义。
6. 属性访问：上游 ctx[key] 由 Proxy 中介；Cordis4j 改为中介声明式 fiber 的 get/find（D13）；
   注解式注入仍为后续工作。
7. 事件过滤器：以 Context.on(type, filter, listener) 的每监听器谓词提供（D16）；不镜像上游的
   声明式过滤器注册表。
8. Logger/logger(name)：与上游内建日志服务最小对齐（java.util.logging 适配）的零依赖实现。
9. 反应式重激活复用同一 fiber（每次激活使用全新效应域）；论文的 reload 同样保持 fiber 身份，
   但上游 TS 会重建插件实例——因此回调必须可安全重跑。

---

## 6. 边界语义（逐条由测试固定）

1. dispose 幂等：重复 dispose 是 no-op。
2. dispose 重入：撤销过程中再 dispose 同一域/上下文是 no-op。
3. 已 dispose 的上下文：get/emit/plugin/fork/provide/effect/isolate 抛 IllegalStateException。
4. provide 覆盖：同键再次 provide 替换绑定；旧句柄变 no-op；被替换服务的 stop() 立即执行（扩展 D9）。
5. null 拒绝：全部公共 API 以 NullPointerException 拒绝 null（requireNonNull）。
6. 监听器失败：抛异常的监听器将异常传播给 emit 调用者；其余监听器（含祖先的）不再调用。
7. plugin.apply 失败：已注册效应 LIFO 回滚后异常传播。
8. 查找路径：get 沿树向上；每层查询该类型的 realm 覆盖——存在且不同于键限定符则跳过该层，
   否则查询该层 store。
9. isolate 派生：子继承父的一切，仅给定类型的 realm 被重写；dispose 子即整体丢弃。
10. fork 级联：子的 dispose 注册为父活跃域的效应；父 dispose 时子先于父更早效应撤销（跨 fiber
    树 LIFO，T6）。
11. 事件上浮方向：仅子→祖先；同上下文内监听器按注册顺序执行。
12. 并发：注册表状态由内部锁保护（D19）；用户回调不得阻塞于其他需要树状态的线程（文档声明；
    运行时自身从不如此）。
13. 反应式生命周期：inject fiber 满足即激活；被依赖绑定撤销即反应式卸载；声明再次满足即重激活；
    退役或失败的 fiber 永不重激活（T11、T15）。
14. 排空顺序：卸载 provider 先撤出其供给；每个 dependent——含仍在 LOADING 者——先于 provider
    自身任何效应撤销而卸载，且各 dependent 的 teardown 仍可解析被撤绑定（T12、D20）。
15. 供给唯一性：第二个活跃 fiber 供给已占 store 键抛 SupplyConflictException 且其插件注册回滚；
    ambient provide 自由覆盖（T13）。
16. 声明中介：声明式 fiber 内 get/find 只解析其声明键与自供键；事件不受中介（T14）。
17. guard：spawn 的任务继承其发起者的 fiber；fiber 退役、卸载中/非活跃、或声明停止可解析后
    isDiverted 为真（T20）。
18. pluginAsync 等待激活落地；受检激活失败以 CordisException 包装传播；spawn 任务句柄在域卸载时
    中断并 join（T19）。
19. 事件：父类型监听器接收子类型事件；每监听器过滤器先于监听器执行（T17）。
20. 拦截元数据：全链 InterceptMetadata 从根向查询点合并（冲突近端胜）；混合类型保持
    nearest-wins（T18）。
21. Loader：reconcile 装载新 id、卸载消失 id、重载换实例条目（实例身份即版本）；失败的 reconcile
    恢复上一配置；dispose 按装载逆序卸载（T21）。
22. 重复 provide：同实例同键 provide 两次，第一个移除句柄为 no-op；当前句柄才移除绑定（T23）。
23. 事件模式：once 监听器仅触发一次后自注销（过滤器与手动注销均生效）；prepend 监听器先于
    本 context 既有监听运行，冒泡方向不变；bail 于第一个非 null 折叠结果短路并跳过其后祖先；
    waterfall 折叠非 null 结果（null 保持累加值），无人贡献时原样返回事件（T29）。
24. Intercept 消费：intercepts(key) 沿树收集绑定元数据（根在前、最近在后，不合并）；
    interceptOf(key) 等于该链上的 nearer-wins InterceptMetadata 幺半群；混合类型链保持原始值
    （T31）。
23. 注解式注入：实例的 @Inject 字段构成单一声明（D21）——全部键解析后填充，所依赖供给撤退或
    声明退役时清空，再满足时重填；字段持有激活时刻快照，故 ambient 覆盖不触碰已激活声明，
    而供给 fiber 卸载沿 fiber 级供给关系排空它（T24）。

---

## 7. 生命周期模型

- fiber 状态：INACTIVE / LOADING / ACTIVE / UNLOADING（论文 §4.2，同步形态）。
- 迁移：activate 在全新效应域内运行效应函数（LOADING → ACTIVE）；unload 先撤出供给（排空
  dependents，链式贯穿仍在 LOADING 者），再 LIFO 撤销域，并为可能的再次激活换上全新域；
  激活期失败路由至卸载并冻结 fiber（failed，永不重试）。
- 惯性：unload 遇 LOADING fiber 先等激活落地（§4.3.3）；用户代码始终在注册表监视器之外运行（D19）。
- 异步：pluginAsync/spawn 在虚拟线程上承载激活与长任务；任务句柄是可逆效应（中断 + join）。

---

## 8. 演进策略

- 语义化版本：0.x 期间允许破坏性变更，但每次必须更新本契约、决策日志与 CHANGELOG。
- 跨 P3 的稳定锚点：ServiceKey 形态、Disposable/EffectScope 契约、异常体系、fork 级联语义、
  排空顺序（D20）。
- P3 入口：ModuleLayer HMR 变体（阶段 2）与文件粒度 import 图分类、Quarkus 生态集成
  （已评估并推迟，docs/design/quarkus-evaluation.md）。
- v2.1 已落地：运行时反射注解式注入——`Injects.injectFields` 将 `@Inject` 字段装配为一个
  反应式声明（D21、T24）；事件分发模式——prepend、once、bail、waterfall（D22、T29）——补齐
  上游分发模式的同步子集（对齐基准 docs/design/upstream-parity.md）；intercept 链消费——
  intercepts(key) 即 resolveConfig 的 Java 形态（D23、T31）。
- 生态（模块级，处于本核心契约之外；不追加决策条目）：cordis4j-langchain4j 将会话上下文的
  `CordisTool` 服务暴露为遵循反应式协效应生命周期的 LangChain4j 工具（T25）；cordis4j-spring
  提供 Context bean 与遵循 bean 生命周期的 @CordisService bean（T27）。
- HMR（路线图 c，模块级，处于本核心契约之外；不追加决策条目）：评估
  （docs/design/hmr-evaluation.md）与阶段 1 引擎（cordis4j-hmr）——零依赖自定义 ClassLoader
  引擎，jar 粒度模块分类、加载器 close-and-collect 回收、基于核心 Loader 的事务性重载（T26）。
- 编译期注入（论文 §6.4，模块级，处于本核心契约之外；不追加决策条目）：
  cordis4j-inject-processor 在编译期校验 @Inject 字段（public 顶级类、非
  static/final/原始类型/private 字段、命名包）并经由 `Injects.FieldTarget` 访问器形态为每个类
  生成免反射 injector（T28）。

---

## 9. 参考

- 论文：A Programming Paradigm for Spatiotemporal Composability,
  https://github.com/cordiverse/paper
- 上游：https://github.com/cordiverse/cordis ；@deepseek-ai/cordis@4.0.1（vendored in
  deepseek-harness）
- Koishi 的可逆插件设计：https://koishi.chat/zh-CN/cookbook/design/disposable.html
