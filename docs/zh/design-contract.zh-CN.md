# Cordis4j 设计契约（Design Contract）

> 本文档是英文规范本 [../design-contract.md](../design-contract.md) 的中文译本（规范本语言：英文）。
> 如有歧义，以英文版为准。最近同步：2026-08-17（v2.9，0.4.1 语义澄清批）。
> 状态：**v2.9 冻结**（对应 v0.4.1）。任何语义变更必须经由决策日志（§2）追加新条目并提升版本。
> v2.6 承载 D25/D26；v2.7 归属 D27（HMR 类隔离，随 0.3.0 发布）并修正头部滞后；v2.8 追加
> D28（cordis4j-loader 的 cordis 配置格式桥接，随 0.4.0 发布）与边界语义 35；v2.9 为 0.4.1
> 语义澄清批——边界语义 36-44，及对 D5 键空间说明、边界 29/32/33 与环措辞的修正。
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
| D5 | 服务键 | ServiceKey<T> = (Class<T> type, String qualifier)；qualifier 是 realm 的一维投影；get(Foo.class) 是默认限定符糖 | 为论文 §6.2 多提供者与 loader realm 预留扩展点。因此 realm 名与同文本的 qualifier 是同一个 store key——隔离声明可被带同文本 qualifier 的 ambient 绑定满足（边界 36） |
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
| D23 | Intercept 消费 | Context.intercepts(key) 沿树收集绑定元数据，根在前、最近在后（原始链，不合并）；interceptOf 保持该链上的 nearer-wins 幺半群；调用者以任意策略合并列表；intercept 键设计上不做 realm 重写——元数据寻址而非解析 | 上游 Service.resolveConfig 的 Java 形态——链收集即消费语义，合并策略留在调用方（上游对齐基准） |
| D24 | 注册表视图 | Context.services() 快照本 context 提供的绑定（不含祖先），键为 realm 覆盖后的有效 storeKey；快照不可变；枚举遍历该快照 | 上游 registry values/entries 的类型化形态；解析后的整树视图留待 loader 组合 DSL（对齐 P4-4）需要时再做 |
| D25 | 基础目录 | Context.baseUrl() 返回本 context 或祖先绑定的最近基础目录（无则空）；Context.withBaseUrl(path) 派生携带它的子 context（同 fork 惯例）；相对配置路径（include 引用）以它解析 | 上游 Context.baseUrl 的不可变派生惯用形态（fork/isolate） |
| D26 | Loader 组合 | Loader.reconcileTree 把 ComponentSpec 树展平为逐条目装载上下文并经 D18 引擎调和：Group 以 groupId+':' 前缀其子条目 id；Isolate 把子条目装载进派生的 isolate(type, realm) 上下文（每节点一个域，其条目全部卸载后 dispose）；Include 经调用方提供的 resolver 内联另一配置源（相对基础目录解析，不限定文件格式）；展平重复 id 在任何变更前立即失败；失败回滚同 D18 | 上游 entry/group/isolate/tree 配置与 include 指令的类型化形态；平面 reconcile(LoaderConfig) 是同一引擎的单上下文特例 |
| D27 | HMR 类隔离 | cordis4j-hmr 把每个插件 jar 装入父为 cordis4j-core 加载器的 URLClassLoader：宿主类优先于插件内同名类（插件永远看到宿主 Plugin 类型），插件不能自带宿主依赖的其他版本，跨插件同名类各持副本，无模块封装；回收保持 close-and-collect 与 T26/T34 的 GC 保证 | docs/design/hmr-evaluation.md 第 5 节的阶段 1 模型；child-first（含 cordis4j-core 排除）与 ModuleLayer 升级已在 docs/design/hmr-isolation-evaluation.md 评估并预留——仅在真实需求出现时再动代码 |
| D28 | 格式适配边界 | cordis4j-loader 桥接上游 cordis 配置**格式**——`@cordisjs/plugin-loader` 的条目树形状与 `plugin-include` 的 patch 语义——到核心 D26 组合之上，且仅此而已：读取忠实（`cordis.yml`/`.yaml`/`.json` 根为条目行列表；延迟 `!!js` 标签解析为不透明 JsExpr，由宿主经可插拔 ExpressionEvaluator 插值；未知字段逐字保留；缺省 id 读取时生成——上游 ensureId，但不写回）；patch 层保持上游语义（insert 追加根或定位 group；override 按 id 递归定位；name 不匹配跳过；config 整体替换；同层后继 patch 可见先前 insert）；两个 dsh 清单（`dsh.bundle.patch`、有序 `dsh.profile.bundles`）解析而不集成包管理器；映射把条目的隔离表包装为嵌套 Isolate 域（`true` → `'#'+entryId` 本地域，label → `'@'+label` 共享域，表首服务最外层），disabled 条目退出装载但保留元数据，config/inject/intercept 经 EntryMeta 交宿主；组件与服务名解析是接口（ComponentResolver）——不内置 JS 引擎、不做 npm/registry 客户端、不写回配置 | 格式是 cordis 生态的稳定契约；运行时决策（名字解析为什么、表达式如何求值）在 JVM 上是宿主策略——本模块是格式桥，不是运行时 |

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
        Map<ServiceKey<?>, Object> services()
            // 本 context 提供绑定的不可变快照（不含祖先），键为有效 storeKey（D24）

        -- 空间（§3.3.1）--
        Optional<Path> baseUrl()          // 沿树最近的基础目录，或空（D25）
        Context withBaseUrl(Path)         // 派生携带基础目录的子 context

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

    -- Loader 组合（D26，上游 entry/group/isolate/tree 的类型化形态）--
    sealed interface ComponentSpec
        record Entry(String id, Plugin component)        // 普通组件
        record Group(String id, List<ComponentSpec>)     // 子条目 id 前缀 id+':'
        record Isolate(Class<?> type, String realm, List<ComponentSpec>)
            // 子条目装载进派生 isolate 上下文；每节点一个域，其条目全部卸载后 dispose
        record Include(Path file, Function<Path, List<ComponentSpec>> resolver)
            // 相对基础目录内联另一配置源；resolver 自选文件格式
    Loader.reconcileTree(List<ComponentSpec>) / reconcileTree(Path baseUrl, List<ComponentSpec>)
        // 展平后以逐条目装载上下文跑 D18 引擎

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
25. 注册表视图：services() 只快照本 context 提供的绑定（不含祖先），键为 realm 覆盖后的有效
    storeKey；快照不可变；覆盖与移除反映在后续快照（T32）。
26. 基础目录：baseUrl() 沿树取最近绑定；withBaseUrl(path) 派生子 context 且子孙继承绑定；
    dispose 后两者拒绝（T33）。
27. Loader 组合：group 以 ':' 前缀子条目 id；隔离域把子条目装载进派生上下文，兄弟域无供给
    冲突共存，且域在其条目全部卸载后 dispose；include 相对基础目录内联其源；展平重复 id
    在任何变更前立即失败；组件失败将树形调和回滚到旧集合（T33）。
28. 注解式注入：实例的 @Inject 字段构成单一声明（D21）——全部键解析后填充，所依赖供给撤退或
    声明退役时清空，再满足时重填；字段持有激活时刻快照，故 ambient 覆盖不触碰已激活声明，
    而供给 fiber 卸载沿 fiber 级供给关系排空它（T24）。
29. 分发重入：监听器、过滤器与 fold 函数在总线监视器之外运行（D19）；监听器可在分发中途
    注册、注销、再分发而不死锁；阻塞中的监听器不串行化无关的事件操作；竞争分发下 once
    监听器恰好触发一次（T38）。once 监听器在 CAS 消费之后、执行之前被手动 dispose，该次
    执行仍然进行——业界 once 语义以消费而非送达为承诺点。
30. 隔离 × 注入：依赖声明以有效（realm 重写后）的 store key 索引，声明中介（D13）也在同一
    键基上比较——与 provide、notify、withdraw 所用的键一致——故隔离子树内的反应式依赖者
    正常分类；默认域绑定永不满足隔离声明（隔离对外层默认绑定不可见，与上游一致）（T37）。
31. 注册表释放：对从未跑完完整卸载的 fiber（被反应式排空、失败、从未满足）dispose 其声明
    即将其移出注册表，其 owner 子树因此可回收（T36）。
32. 域复用：loader 以 Isolate 链路径（从根起的嵌套 realm 序列，group 前缀不计）为每个
    隔离域编键，跨 reconcile 复用其派生上下文；group 不构成隔离边界，嵌套内层域不与同
    label 顶层域合并，域内未变化的条目不重载，且仅当整次调和落地后真正排空的域才被
    dispose（T39/T53）。
33. 钩子回滚：首次 provide 期间 Service.start()/stop() 抛出时先移除绑定再传播失败，不留
    调用者无法清理的孤儿；覆盖既有绑定时失败则恢复原绑定（token 与 owner 原样）并尽力
    re-start 旧服务——键不蒸发，依赖者回到覆盖前的稳定态（T40/T51）。
34. 被中断的异步激活：等待 pluginAsync 的调用者被中断后失去句柄，句柄随之进入 ambient——
    无论 fiber 落在何种状态，context 自身的销毁都会卸载它（T41）。
35. 格式层（cordis4j-loader，D28）：读取保留 `!!js` 标签为不求值的 JsExpr、未知字段逐字保留，
    缺省 id 读取时生成（8 位十六进制）；无 id 的 insert 追加根列表，带 id 追加进定位 group
    （目标缺失、非 group 或畸形均告警跳过该 patch）；override 按 id 在树内任意位置定位
    ——name 存在但不匹配则跳过该 patch，config 整体替换，目标缺失仅告警不失败；同层
    后续 patch 可定位先前 insert 的行；无 `dsh` 键的包不声明任何内容；映射把 `true` 变成本地域
    `#<entryId>`、label 变成共享域 `@<label>`（表首服务最外层），disabled 条目（含 group 自身
    开关沿链继承）退出装载但保留元数据，无 id 条目拒绝映射（T42-T45）。
36. realm/qualifier 键空间（D5）：realm 名与同文本 qualifier 是同一个 store key——隔离声明
    重写为该键，带同文本 qualifier 的 ambient 绑定满足它，撤回该绑定 drain 依赖者，再提供
    再激活（T46）。
37. 声明中介基（D13）：比较所用有效键以取数解析经过的 context 计算，而非声明所在的
    owner——realm 声明的 fiber 经 root 取 default 键被拒，反向亦然（T47）。
38. 竞态退役：在 notifyBound 选定与激活之间被退役的声明永不执行 body；与 context dispose
    竞态的被中断 pluginAsync 调用者仍收到 CordisException，孤儿 fiber 在处理分支内就地
    退休并卸载（T48/T49）。
39. dispose 完成性：ambient 阶段抛异常的 context dispose 仍关闭其 executor（关闭失败聚合为
    suppressed），loader 域计数在组件 teardown 抛异常时照常落地，排空的域真正废弃（T50）。
40. 覆盖回滚：见边界 33——旧绑定被恢复而非蒸发（T51）。
41. 环依赖：互相环的声明永不满足、静默保持 INACTIVE，与上游一致——不抛任何异常；同步重入
    守卫只对自环存在（body 提供自己 fiber 声明的键，且选择器自身已阻止该场景）（T52）。
42. loader 域键与 group 继承（D28）：域键为不含 group 前缀的 Isolate 链路径（边界 32）；
    group 的 isolate/intercept 表沿原型链下传，近端行覆盖同服务名；falsy 隔离 label
    （null、false、空串）不产生域，非字符串非布尔 label fail-fast（T53/T54）。
43. patch 表与目标（D28）：override 的 intercept/isolate 表整替目标的表（缺省保留目标的；
    与上游不同，null 无法清空字段——Java record 无法表达字段存在性），extras 按键合并，
    破损的 insert 目标告警跳过（T55）。
44. 展平元数据键（D28）：EntryMeta 以展平 id（含 group 前缀）为键，重复展平 id fail-fast，
    该键可端到端 join 已调和树（T56）。

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
  intercepts(key) 即 resolveConfig 的 Java 形态（D23、T31）；注册表视图——services() 即上游
  注册表枚举的类型化形态（D24、T32）；loader 组合 DSL——D18 引擎之上的
  group/isolate/tree/include 与 baseUrl 派生（D25、D26、T33）。
- 生态（模块级，处于本核心契约之外；不追加决策条目）：cordis4j-langchain4j 将会话上下文的
  `CordisTool` 服务暴露为遵循反应式协效应生命周期的 LangChain4j 工具（T25）；cordis4j-spring
  提供 Context bean 与遵循 bean 生命周期的 @CordisService bean，并在任何 bean 销毁前的 stop
  阶段撤回绑定，使 drain 保持边界 13/14（T27、T35）。
- 格式桥接（模块级，决策 D28）：cordis4j-loader 读取 cordis 配置格式——含延迟 `!!js` 标签的
  条目树、patch 层、dsh 双清单——并映射到 D26 组合；组件解析与表达式求值保留为宿主策略
  （T42-T45）。本模块依赖 snakeyaml 与 jackson-databind；核心保持零依赖。
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
