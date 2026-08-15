# 上游对齐基准：以 cordiverse/cordis 为锚

> 本文档是英文规范本 [../design/upstream-parity.md](../design/upstream-parity.md) 的中文译本
> （规范本语言：英文）。如有歧义，以英文版为准。最近同步：2026-08-15（v1 初版）。
> 状态：cordiverse/cordis@main（2026 年 8 月，9 个 package）对 Cordis4j 的基线快照。
> 本基准锚定目标：Cordis4j 应具备与 Cordis 仓库同等的能力，同时保持 JVM 优势。它是随双方演进
> 更新的活基线；上游实现与论文分歧时，论文仍是语义锚。

## 1. 方法

- 只读盘点上游每个 package 与其公开 API 面。
- 每项上游能力分类：已对齐 / 部分对齐 / 有意差异（JVM 优势）/ 缺失 / 不适用。
- 缺失项给出计划（P4-1…），落地于独立模块或契约条目；core 保持零依赖。

## 2. 盘点

### 2.1 @cordisjs/core（论文核心库的参考实现）

| 上游 | Cordis4j | 状态 |
|---|---|---|
| Proxy 后的内建服务（`ctx.logger`、`ctx.events`、`ctx.reflect`、`ctx.registry`，字符串命名） | `ServiceKey<T>(type, qualifier)` + 类型化 `get`/`find`（D1、D5） | 有意差异：类型键取代字符串名——JVM 优势 |
| `Context.root` / 原型链 `extend(meta)` | `root()`、`fork()`、`isolate(type, realm)` | 已对齐（Java 形态） |
| `Context.baseUrl`（配置文件目录） | 无 | 缺失；随 loader DSL 补（P4-2） |
| 事件：`on`（prepend/priority 选项）、`once`、分发模式 `emit` / `bail` / `waterfall` / `parallel` / `serial` | 同步 `on` + 每监听器过滤器、`emit` | 部分对齐：缺 prepend/once/bail/waterfall（T29，P4-1）；parallel/serial 是异步分发——不适用于同步核心，待异步形态再议 |
| Logger：name 层级、级别、diff、exporter 扩展 | `logger(name)` + java.util.logging 适配 | 部分对齐：级别与 name 有；exporter 扩展点有意缺席（JVM 日志生态——SLF4J/JUL——即 exporter） |
| 注册表枚举（`get/has/delete/keys/values/entries/forEach`） | 无枚举 API | 缺失（P4-2：类型键之上的注册表视图） |
| `Inject` 装饰器 + `ctx.inject` 反应式声明 | `ctx.inject` + `@Inject` 字段 + 编译期处理器（D21、T24、T28） | 已对齐并超越（编译期生成） |
| `plugin(plugin, config)` + `Service.resolveConfig`（intercept 链配置合并） | `plugin(Plugin)` + intercept 元数据存储（D17）；消费语义未硬化 | 部分对齐：配置解析语义缺失（P4-3） |
| `Service` 基类：name/config/invoke/check/tracker | `Service` 标记接口 + start/stop 钩子（D9） | 有意差异：invoke（可调用服务）与弱类型配置是 TS 惯用；Java 服务用构造器与类型化配置对象 |
| Fiber 运行时，rc6 的 shadow/caller 观测 | fiber 状态机（D7/D19/D20），无 shadow 观测 | 有意差异：shadow/caller 服务于上游 Logger 观测；JVM logger 已简化——记录，需要观测时再议 |
| `reflect` 服务（Proxy 后字符串名提供） | 无 | 有意差异：被类型键取代 |

### 2.2 @cordisjs/loader

| 上游 | Cordis4j | 状态 |
|---|---|---|
| `Loader` 与 `EntryTree` 配置：`entry` / `group` / `isolate` / `tree` 组合、事务性调和 | `Loader`/`LoaderConfig`/`ComponentEntry`：id 键控 diff、事务性调和、逆序卸载（D18、T21） | 部分对齐：调和引擎有；组合配置 DSL（group/isolate/tree/include）缺失（P4-2） |
| YAML `include` 指令（`@cordisjs/include`） | 无 | 缺失（P4-2） |

### 2.3 @cordisjs/hmr

| 上游 | Cordis4j | 状态 |
|---|---|---|
| Algorithm 8/9/10：文件粒度模块分类、stale 检测、事务性重载 | `cordis4j-hmr`：jar 粒度分类、加载器 close-and-collect、基于核心 Loader 的事务性重载（T26） | JVM 形态已对齐；文件粒度 import 图是 JS 模块系统特性——已记录为 ModuleLayer/文件粒度阶段 2（docs/design/hmr-evaluation.md） |

### 2.4 @cordisjs/timer

| 上游 | Cordis4j | 状态 |
|---|---|---|
| `TimerService`：`setTimeout`/`setInterval`（受跟踪，dispose 时撤销）、`timeout`/`interval` promise 形态 | 无 | 缺失（P4-1/T30：cordis4j-timer 模块，基于核心 spawn/任务模型的可逆定时器） |

### 2.5 @cordisjs/logger-console

上游 Logger 的控制台 exporter。Cordis4j 的 Logger 适配 java.util.logging，控制台输出来自 JVM
日志配置（JUL handler、SLF4J 桥接）——exporter 的 JVM 等价物。有意差异；无需移植。

### 2.6 @cordisjs/group、@cordisjs/utils、@cordisjs/create

- group：注册表的服务分组视图——并入 P4-2（注册表视图）。
- utils：上游内部工具——不适用。
- create：项目脚手架——生态工具而非库能力；推迟。

## 3. 已发挥的 JVM 优势（保持）

1. 类型化服务键 + 编译期校验（`ServiceKey`、T28 处理器），取代字符串键与 Proxy。
2. 按关注点拆分的 JPMS 模块；core 保持零依赖。
3. 虚拟线程异步 + guard 协议（D15），取代事件循环。
4. 可证明的字节码回收：close-and-collect + 弱引用观察类加载器（T26）。
5. 核心 Loader 的事务性调和（T21）与生态桥接（Spring、LangChain4j）。

## 4. 计划（P4）

- **P4-1（事件模式，T29）**：同步事件总线上的 `once`、prepend 优先级、`bail`（短路）、
  `waterfall`（折叠返回）——上游分发模式的同步子集。契约 v2.2，决策 D22。
- **P4-2（定时器模块，T30）**：`cordis4j-timer`，可逆 `setTimeout`/`setInterval`（受跟踪效应，
  域卸载时取消）与其 promise 形态，镜像 `@cordisjs/timer` 于 spawn/任务模型之上。
- **P4-3（配置解析）**：intercept 元数据的消费语义——服务沿链解析的配置，`Service.resolveConfig`
  的 Java 形态。
- **P4-4（loader DSL + 注册表视图）**：group/isolate/tree 组合、`include` 指令、类型化注册表
  枚举——`@cordisjs/loader` 与 `@cordisjs/group` 的组合半程。
