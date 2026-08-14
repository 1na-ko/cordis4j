# Cordis4j 可行性评估报告

> 评估对象：`docs/cordis4j-p0-p1-plan.md`（P0–P1 实施计划草稿）
> 依据：cordis 论文《A Programming Paradigm for Spatiotemporal Composability》（cordiverse/paper）、
> 上游参考实现 @deepseek-ai/cordis@4.0.1（DSH 内核实际依赖，MIT）、GitHub 生态调研。
> 结论先行：**核心语义（P0–P1）可行性高，风险可控；代码级热替换是唯一真正难点，方案已正确后置。**

---

## 1. Cordis 内核是什么（对齐理解）

论文将 Cordis 定义为 spatiotemporal composability 的 meta-framework，分两层：

1. **核心库（core library）**：三个正交机制
   - **可逆效应（revertible effects，§3.1/§5.1.1）**：所有上下文变更走唯一原语 `ctx.effect`，每次变更携带显式逆，运行时按 LIFO 累积（Algorithm 1：`inverse ← value ∘ inverse`），卸载时整体恢复。
   - **反应式协效应（reactive coeffects，§3.2/§5.1.2）**：组件声明依赖（inject），供给变化时按 satisfaction 谓词分类为 activating / deactivating / neutral 并通知（Algorithm 3）；realm 机制支持隔离（isolate）与拦截（intercept）。
   - **组件生命周期（§4/§5.1.3）**：fiber = 协效应规格 + 效应函数；惯性状态机（inertia，§4.3.3）保证一次迁移完成前不响应新目标；卸载顺序保证"provider 先停止提供 → 排空 dependents → 再逆序撤销自身效应"（Algorithm 5 Line 25，Theorem 63）。
2. **组件加载器（loader，§5.2）**：声明式配置调和（条目树、id 键控 diff）+ HMR（三阶段：模块分类、陈旧条目检测、事务性重载）。

统一上下文（§3.3.1，Γ∞ = 递归上下文 × 累积器 × 协效应上下文）把两者合并为一个类型：fork 产生隔离子上下文（子可见父、父不可见子），dispose 恢复整棵子树。

**上游实现规模**：@deepseek-ai/cordis@4.0.1 核心仅 **9 个源文件**（context/events/fiber/logger/reflect/registry/service/utils）。配套插件族：loader、hmr、include、group、timer。论文案例 Koishi 生态 4000+ 插件。

## 2. GitHub 调研：无同类产品，JVM 上存在真实空白

调研方式：GitHub 仓库搜索 + 论文 §7 相关工作清单 + JVM 生态定向检索（沙箱阻断直连 GitHub API，改用多轮 web 检索交叉验证）。

### 2.1 直接移植：不存在

- **cordis4j / cordis-java / 任何 JVM 移植：未发现**（仓库名、readme、topic 检索均为空）。
- 上游生态仅 TS：cordiverse/cordis（MIT）、koishijs/koishi、@deepseek-ai/cordis（DSH vendored 版，v4.0.1）。
- 结论：命名 `Cordis4j` 无冲突，市场空白，先发者可定义 JVM 侧的事实标准。

### 2.2 JVM 近亲对照（论文 §7 已给出坐标系）

| 产品 | 差距 | GitHub |
|---|---|---|
| **pf4j**（Java 插件框架） | 有插件生命周期 + 类加载器隔离 + 扩展点；**无 DI、无效应追踪、无反应式重连、无 fork 隔离**。最接近的"插件框架"但只覆盖 cordis 的一小块 | github.com/pf4j/pf4j |
| **OSGi Declarative Services / iPOJO**（论文 §7.4 承认的"最接近先例"） | 服务按可用性自动激活/停用，方向一致；但清理靠**手写回调**（泄漏靠纪律）、同步、无 LIFO 效应恢复、无 realm 隔离语义。生态在 JVM 已边缘化 | github.com/apache/felix |
| **Spring / Guice / Koin / Micronaut** | 初始化期一次性 DI；运行时替换/移除 provider **不触发** dependent 重解析或卸载（论文 §7.4 原文结论） | 各自官方仓库 |
| **Java 9 ModuleLayer + HotswapAgent / JRebel** | 字节码/模块层热替换的原料（P3 可借鉴），不是可组合性框架 | github.com/HotswapProjects/HotswapAgent |
| **Scala ZIO / Kyo，Kotlin Arrow**（效应系统） | 代数效应，但无动态卸载 + 协效应解析 + 依赖重连组合 | 各自官方仓库 |

**定位结论**：JVM 上目前无人占据"可逆效应 + 反应式协效应 + 动态可卸载"这个组合定位。Cordis4j 不是再造一个 DI 框架，而是填补 JVM 的动态组合基础设施空白。

### 2.3 LangChain4j 类比：成立，但有一个关键差异

- 成立的一面：LangChain4j 的成功模式是**重想 API 而非逐行移植** + 多模块生态（core → 各 provider 集成 → Spring/Quarkus 集成模块）。方案已采用同路线（"inspired-by cordis，不逐行对齐 TS API"、运行时零依赖、以 Class 为键的 Java 化 DI）——这是正确选择。
- 差异：LangChain4j 背靠 Java 企业 AI 集成刚需；Cordis4j 的价值主张更窄，需要自造杀手场景。方案 R4（LangChain4j 集成演示"会话内动态装卸 tool"）方向正确；更贴合论文 §1.2.2 的原始动机是 **JVM 上的 agent harness 自进化**——那是 DSH 场景在 Java 的对应物，建议作为 README 的远景叙事。

## 3. 可行性论证：论文 §6.4 的"语言最低要求"逐条对照 Java

论文 §6.4 明确列出移植到新语言的前提，逐条核对：

| 论文要求 | Java 落点 | 判定 |
|---|---|---|
| 闭包承载逆（inverse 作为值可重放） | lambda / `Disposable extends AutoCloseable` | ✅ 天然满足 |
| 依赖声明 + 访问中介 | `Class<T>` 键 + `ctx.get`；`ctx[key]` 的 Proxy 动态属性 Java 没有，但 §6.4 明说"注解 + 编译期生成访问器"是替代路径 → P2 用 `@Inject` 注解 + 动态代理/APT | ✅（P2 走注解路线） |
| 类型层面键 | Java 的 `Class<T>` **同时**解决键身份与类型约束，比 TS 字符串键 + module augmentation 更自然 | ✅ 反而更优 |
| 运行时引入/回收模块（HMR 前提） | JVM 无 require.cache 式模块注册表回收；只能自定义 ClassLoader 丢弃 + GC（OSGi 已验证可行但笨重），或 ModuleLayer | ⚠️ **唯一硬难点**，方案已正确后置 P3 |
| 异步迁移的惰性调度（inertia） | Java 21 虚拟线程 / 结构化并发 | ✅（P2） |
| 观察等价（≃） | 无语义负担，属设计约定 | ✅ |

**核心工作量**：上游核心 9 个 TS 文件；方案估算 Context 实现 300–500 行、每个子系统 ≤ 1 类、零运行时依赖——与上游规模相符，单人 + agent 辅助的 1–2 天（M2）估算合理偏乐观（建议留 2–3 天缓冲）。

## 4. 方案评估：正确的切割 + 五处需修正

### 4.1 方案做得对的地方

- **范围切割正确**：两态生命周期先行、惯性状态机/异步/声明式加载器后置、HMR 明确为 P2 验收标准而非 P0–P1 目标（"核心先行、热重载后置"完全符合论文 §5.1.3 与 §5.2 的分层）。
- **语义对齐点准确**：T1（LIFO/幂等，§3.1.2）、T2（fork 隔离，§3.3.1）、T4（Algorithm 4 的 fiber 效应归属）、T5（§4.3.4 Failure 简化）、D3 事件上溯与 cordis 实际行为一致（子 emit 触发父监听，反向不触发）。
- **风险清单（R1–R4）质量高**，尤其 R1"以论文正式语义为唯一对齐目标"——这是移植项目的正确姿态。
- 零运行时依赖、以测试即文档、demo ≤ 60 行：与上游核心的精简气质一致。

### 4.2 需修正/补充的决策点

- **D5（新增）键设计须预留 realm 扩展**：方案以 `Class<T>` 为键，丢失了上游的 realm 语义——同一接口多提供者（论文 §6.2 服务多路复用、loader 的 isolate 字符串 realm）。P1 单 realm 可接受，但契约中必须预留 `(Class, qualifier)` 或 `ServiceKey<T>` 形态的扩展点，否则 P2 隔离语义会返工。这是**最大的隐蔽风险**。
- **T6 测错了顺序语义**：`Service.start/stop 按注册顺序/逆序` 是 Spring 式扩展语义，**论文没有这个保证**；论文真正保证的顺序是 **provider 卸载前先排空 dependents**（Algorithm 5 Line 25 + Theorem 63：provider 进入 UNLOADING 即停止提供 → dependents 先重算并卸载 → provider 才逆序撤销）。建议 T6 改为测"provider 移除 → dependent 先卸载 → provider 效应后撤销"的排空顺序；start/stop 钩子降级为可选的 Java 化扩展并显式标注为偏离。
- **get() 异常语义与上游有偏差**：上游 `ctx.get(key)`（store 查询）从不失败；报错的是 Proxy 属性访问（INACTIVE_ACCESS / UNDECLARED_ACCESS，Algorithm 6）。方案 `get()` 抛异常 + `find()` 返回 Optional 是合理的 Java 化，但建议异常类型拆成 `NoSuchServiceException` 与 `InactiveAccessException` 两种，对应上游两类错误，便于未来对齐依赖声明校验。
- **D4 命名**：`io.cordis4j` 无冲突（检索确认无同名项目），可定稿；建议同时注册 `org.cordis4j` 以防域名争议，二选一后不动。
- **许可证与署名**：上游 MIT，无移植障碍；README 应注明"语义依据 cordiverse/paper，MIT；上游实现 cordiverse/cordis 与 @deepseek-ai/cordis"。

### 4.3 架构补充建议（LangChain4j 式多模块，P1 单模块先跑）

P1 保持单模块，但内部 package 按未来模块边界划分：`core.effect` / `core.coeffect` / `core.event` / `core.lifecycle`。P2+ 拆为：`cordis4j-core`（本计划）→ `cordis4j-loader`（声明式配置调和）→ `cordis4j-langchain4j` / `cordis4j-spring` / `cordis4j-quarkus`（集成与 killer demo）→ P3 `cordis4j-hmr`（ClassLoader/ModuleLayer 方案评估）。

## 5. 结论

**可行。** 三重依据：

1. **语义有形式化**：论文 §3–§5 给出完整可移植的语义规范（含 5 个伪代码算法），不依赖 TS 实现细节；
2. **实现有先例**：上游核心仅 9 文件，Koishi 4000+ 插件 + DSH 自身是生产验证；
3. **难点有边界**：唯一硬难点（代码级热替换）方案已正确后置 P3，且 OSGi/ModuleLayer 证明 JVM 可行；P0–P1 范围全部落在 §6.4 的"低要求区"内。

**建议**：按方案推进 P0–P1，采纳 §4.2 的五处修正（尤其 D5 键扩展点与 T6 排空顺序测试）。市场空白 + 论文背书 + 上游生产案例，Cordis4j 有成为"JVM 动态组合基础设施"定义者的窗口期。
