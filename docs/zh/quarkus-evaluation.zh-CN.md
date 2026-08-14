# Quarkus 集成：评估

> 本文档是英文规范本 [../design/quarkus-evaluation.md](../design/quarkus-evaluation.md) 的中文译本
> （规范本语言：英文）。如有歧义，以英文版为准。最近同步：2026-08-15（v1 初版）。
> 状态：评估（路线图 P3 第 d 项，Quarkus 半程）。本文档记录集成路径与建议，是给维护者的建议，
> 不是决策日志条目。未来实现将落在独立模块，cordis4j-core 保持零依赖。

## 1. 集成必须表达什么

范式的两个维度映射到 Quarkus 的编程模型：

- **时间维**：cordis4j `Context`（通常每会话或应用作用域一个），其 dispose 撤回它携带的全部
  插件、服务与子上下文——映射到 CDI 上下文生命周期（应用/关闭、会话/销毁）。
- **空间维**：Quarkus bean 声明自己为 cordis4j 服务，可解析、可反应式（满足/通知/刷新），
  同时仍是普通 CDI bean。

Spring 模块（cordis4j-spring，T27）已经展示了这一集成的确切形态：context bean 销毁时 dispose
context，注解 bean 提供进 context 并按提供逆序撤回。Quarkus 的问题是它的哪个扩展机制最能承载
这一形态。

## 2. 候选机制

| 机制 | 提供的 | 成本 |
|---|---|---|
| 纯 CDI producer（`@Produces`） | `@Produces @ApplicationScoped Context` + 返回根的 producer 方法；`@CordisService` bean 经 producer 观察者或 bean 管理的 registrar 提供 | 小；跨 CDI 实现可移植；无 Quarkus 特有构建步骤 |
| CDI 可移植扩展（`Extension`） | 容器生命周期钩子（BeforeBeanDiscovery/AfterDeploymentValidation）发现 `@CordisService` bean 并以编程方式注册 context bean | 中；标准 CDI；需 bean-manager 交互（creational contexts） |
| 完整 Quarkus 扩展（`BuildStep`、`BeanDefiningAnnotation`） | 构建时发现、`BeanDefiningAnnotation` 使 `@CordisService` 成为 bean 定义注解、扩展元数据（`quarkus-extension.yaml`）、处理器运行 | 高；Quarkus 特有基础设施、仅构建时、要求扩展发布 |

## 3. 建议

Quarkus 半程实现为**纯 CDI 模块**（第一行），而非完整 Quarkus 扩展：`@Produces` 式 context bean
加上 CDI `Extension` 观察 `@CordisService` 注解 bean 并将其注册进产出的 `Context`，镜像
cordis4j-spring 的 registrar。这使模块跨 CDI 运行时可移植、无需构建时步骤，并与已发布的先例
一致。完整 Quarkus 扩展（构建时 `BeanDefiningAnnotation`）在构建时发现成为真实需求时可作为
后续。

该项工作是**推迟而非受阻**：cordis4j-spring 今日已覆盖同一集成模式，核心契约不受影响
（集成位于独立模块）。当具体 Quarkus 部署需要该桥接时再启动。

## 4. 参考

- cordis4j-spring（已发布的先例）：cordis4j-spring 模块，T27
- CDI 规范：https://jakarta.ee/specifications/cdi/
- Quarkus 扩展指南：https://quarkus.io/guides/writing-extensions
