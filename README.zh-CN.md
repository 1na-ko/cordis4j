# Cordis4j

[![CI](https://github.com/1na-ko/cordis4j/actions/workflows/ci.yml/badge.svg)](https://github.com/1na-ko/cordis4j/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/require-JDK%2021+-blue.svg)](pom.xml)

> 本文档是英文规范本 [README.md](README.md) 的中文译本；如有出入，以英文版为准。最近同步：2026-08-14。

**Cordis4j** 是 [Cordis](https://github.com/cordiverse/cordis) 元框架在 JVM 上的实现，实现"时空可组合性"
——它正是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 底层内核的语义对应物：

- **时间维**：每次上下文变更都携带被跟踪的逆，卸载时按 LIFO 整体恢复（可逆效应）。
- **空间维**：依赖被声明并**反应式**解析——组件在声明满足时激活，供给被撤销时按依赖序排空卸载，
  依赖回归时重新激活（反应式协效应）。

```java
Context ctx = Contexts.create();

// 反应式组件：只有数据库插件在场时才上线
ctx.inject(Database.class, (c, db) -> {
  c.provide(new Cache(db));                    // 撤销时自动回滚
  return Disposables.of(() -> log("cache offline"));
});

Disposable db = ctx.plugin(new DatabasePlugin());   // → cache 激活
db.dispose();                                       // → dependents 先排空，provider 后撤销
```

> 状态：v0.2.0（**孵化期**）。语义遵循论文
> [A Programming Paradigm for Spatiotemporal Composability](https://github.com/cordiverse/paper)
> 第 3–5 节（Algorithm 1–6）的形式化模型；API 是 Java 化的重想（inspired-by），不是对
> TypeScript 代码的逐行移植。冻结契约与决策日志见
> [docs/design-contract.md](docs/design-contract.md)。

## 这是什么（与不是什么）

**是** - Cordis 论文语义的零依赖、忠实 JVM 实现，面向需要在运行时自我重连的长驻宿主：
卸载一个运行中的组件，其副作用按构造保证被撤销；撤销一个 provider，所有依赖它的组件先按
依赖序排空卸载，依赖回归时自动重新激活。该范式已在生产中得到验证：[Koishi](https://koishi.chat)
（4000+ 社区插件）与 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 内核。

**不是** - Spring 的竞争者。如果你的组件启动后从不变化，静态 DI（Spring、Guice、Dagger 等）
已经覆盖你的需求，Cordis4j 只会是一个更小的 DI 容器。它的价值恰好在静态装配表达不了的场景：
运行时卸载及其保证性撤销，以及 provider 出现/消失/被替换时依赖方的反应式重连。

## 环境要求

- JDK 21+
- Maven 3.9+

## 模块

- `cordis4j-core` - 零依赖核心库（JPMS 模块 `io.cordis4j.core`）：效应、反应式协效应、
  fiber 生命周期、虚拟线程异步、声明式加载器。
- `cordis4j-demo` - 端到端演示。
- `cordis4j-langchain4j` - LangChain4j 工具桥接：会话工具随插件装载、热卸载、换实现
  （仅依赖 langchain4j-core，无模型提供方，可离线运行）。
- `cordis4j-hmr` - 字节码级热模块替换：插件 jar 装入每插件独立类加载器，重载时事务性
  换 fiber，被替换的代码可被回收。
- `cordis4j-spring` - Spring 集成：Context bean 与 @CordisService bean，其绑定跟随容器
  生命周期（仅依赖 spring-beans；核心零改动）。
- `cordis4j-inject-processor` - 注解注入的编译期生成：编译期校验 @Inject 字段并为每个类
  生成免反射 injector。
- `cordis4j-timer` - 基于 spawn 模型的可逆定时器：一次性与周期回调都是"启动即效应、撤销即停止"。

## 论文概念覆盖度（→ Cordis4j）

| 论文构造 | 状态 | 对应 API |
|---|---|---|
| 可逆效应、LIFO 累积器（§3.1, Alg. 1） | ✅ | `EffectScope`、`Disposable` |
| 反应式协效应：满足/通知/刷新（§3.2, Alg. 3） | ✅ | `Context.inject` |
| 事件分发模式：prepend、once、bail、waterfall（上游对齐） | ✅ | `Context.on/once/fold/bail/waterfall`（D22） |
| 注解式注入（§6.4） | ✅ | `@Inject`、`Injects.injectFields`；编译期生成见 `cordis4j-inject-processor` |
| 撤退排空、provider 卸载顺序（§4.3.1, Th. 63） | ✅ | 卸载时自动执行 |
| 供给唯一性（§4.2） | ✅ | `SupplyConflictException` |
| 声明中介 / 能力式访问（Alg. 6） | ✅ | 声明式 fiber 内强制 |
| 失败路由、永不重试（§4.3.4） | ✅ | 记录 + 日志，不传播 |
| 惯性：飞行中 fiber 的链式卸载（§4.3.3） | ✅ | 卸载等待落地 |
| 虚拟线程异步 + guard/divert（§4.3.2） | ✅ | `pluginAsync`、`spawn`、`currentFiber` |
| 隔离 realm + 拦截元数据幺半群（§5.1.2） | ✅ | `isolate`、`InterceptMetadata` |
| 声明式加载器、id 键控 diff、事务性重载（§5.2.1, Alg. 10） | ✅ | `Loader` |
| Loader 组合 DSL：group/isolate/tree/include（上游对齐） | ✅ | `ComponentSpec`、`reconcileTree`（D26） |
| 字节码级热模块替换（§5.2.2） | ✅ | `cordis4j-hmr` 的 `HotReloadingLoader` |
| LangChain4j 工具桥接（生态） | ✅ | `cordis4j-langchain4j` 的 `CordisToolRegistry` |
| Spring 集成（生态） | ✅ | `cordis4j-spring` 的 `ContextFactoryBean` |

## 快速开始与演示

参见 `cordis4j-demo/src/main/java/io/cordis4j/demo/`：

- `QuickStart` - fork 会话、事件、dispose 逆序撤销子树。
- `ReactiveCompositionDemo` - 缓存组件随数据库插件上下线。
- `MultiTenantDemo` - 租户级 realm 隔离（会话沙箱模式）。
- `HotReloadDemo` - 配置调和与事务性回滚。
- `AgentHarnessDemo` - 一切皆插件：反应式工具、虚拟线程 agent loop、guard、
  一次 dispose 撤销整个会话。

运行默认演示（`QuickStart`）：

```console
mvn install -DskipTests    # 先把 cordis4j-core 安装到本地仓库（只需一次）
mvn -pl cordis4j-demo exec:java
```

运行其他演示：加 `-Dexec.mainClass=io.cordis4j.demo.<DemoName>`。

`cordis4j-langchain4j` 附带 `SessionToolDemo`（agent 工具随会话装载、热卸载、换实现）：
`mvn -pl cordis4j-langchain4j exec:java`。

## 构建与质量门禁

```console
mvn verify   # enforcer + spotless + 测试（T1-T33，共 129 个）+ jacoco（>= 85%）+ javadoc + 依赖分析
```

## 路线图

- **P4（上游对齐）** - 对齐基准 docs/design/upstream-parity.md 已全部落地：事件模式（D22）、
  定时器模块、intercept 链消费（D23）、注册表视图（D24）、loader 组合 DSL 与 baseUrl
  （D25/D26）。
- **P3** - ModuleLayer HMR 变体（`docs/design/hmr-evaluation.md` 的阶段 2；阶段 1 零依赖
  ClassLoader 引擎已落地为 `cordis4j-hmr`）与 Quarkus 生态集成（已评估并推迟，
  `docs/design/quarkus-evaluation.md`）。其余 P3 项已落地：注解注入（运行时
  `@Inject`/`Injects` 与编译期 `cordis4j-inject-processor`）、LangChain4j
  （`cordis4j-langchain4j`）、Spring（`cordis4j-spring`）。

## 致谢

Cordis4j 的语义基于 Cordis 论文
（[github.com/cordiverse/paper](https://github.com/cordiverse/paper)）与参考实现
[cordiverse/cordis](https://github.com/cordiverse/cordis) 和 `@deepseek-ai/cordis`
（代码均为 MIT 许可）。Cordis4j 本身以 [MIT 许可](LICENSE) 发布。
