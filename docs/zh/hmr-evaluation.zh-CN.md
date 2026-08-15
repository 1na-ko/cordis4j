# JVM 上的字节码级 HMR：pf4j / OSGi / ModuleLayer 方案评估

> 本文档是英文规范本 [../design/hmr-evaluation.md](../design/hmr-evaluation.md) 的中文译本
> （规范本语言：英文）。如有歧义，以英文版为准。最近同步：2026-08-15（v1 初版）。
> 状态：评估（路线图 P3 第 c 项，前半程：先文档后代码）。本文档记录对比结论与推荐路径，
> 是给维护者的建议，不是决策日志条目。落实推荐方案将落在独立模块，cordis4j-core 保持零依赖。

## 1. 论文要求（锚点）

论文 §5.2.2 把可逆效应模式提升到模块级：代码变化时，系统原地替换受影响组件，无需重启、
无需开发者标注接受边界。其引擎分三阶段：

- **Algorithm 8，模块分类**：由 stashed 集（已变更文件）与 externals 集（不可热替换、触发全量
  重启的模块）出发，以不动点把每个模块分类为 accepted 或 declined。
- **Algorithm 9，stale 条目检测**：组件条目的依赖树与 accepted 集相交，则该条目为 stale。
- **Algorithm 10，事务性模块重载**：带备份地失效 accepted 模块的缓存，然后对每个 stale 条目
  dispose 旧 fiber、用重新 import 的模块实例化新 fiber；任何失败恢复缓存并从备份重建全部
  stale 条目。

§6.4 指明 JVM 特有的前提：时间可组合性要求代码本身在运行时可引入、可回收——在托管运行时
这意味着一个模块注册表，其条目在不可达后可被逐出并回收。JVM 没有这种注册表，所以下述机制
必须自己提供：加载插件代码是一个效应，其逆是类加载器变得不可达、其类可被收集。

Cordis4j 已在**配置级**实现了 Algorithm 10（`Loader` / `ComponentEntry`，决策 D18，T21）：
id 键控 diff、实例身份即版本、事务性调和与回滚。缺的是**字节码级**：把变更的 class 文件
（或 jar）变成重新 import 的 `Plugin`，其新 fiber 替换旧 fiber，且旧类可证明地可卸载。
论文的文件级 import 图在 JVM 上没有直接对应物，因此 JVM 形态以 jar（或类目录）为模块顶点；
依赖图退化为加载器边界，externals 集为显式配置。

## 2. 候选方案

### 2.1 自定义 ClassLoader（纯 JDK）

每个插件一个类加载器（`URLClassLoader`，父加载器为 cordis4j-core 的加载器；或自定义
child-first 变体）加载插件类；`Plugin`（及全部核心类型）来自父加载器，双方共享同一类型身份。

- 引入：实例化加载器并加载插件类。
- 回收：丢弃全部强引用（加载器、类、实例、线程）等 GC 收集；卸载可通过加载器上的
  `PhantomReference`/`ReferenceQueue` 观察。
- 隔离：平坦 classpath 语义；跨插件同名类冲突，除非刻意采用 child-first 策略。
- 成本/风险：最低——零依赖、完全可控；但"类可收集"是纪律（不得泄漏线程、定时器、静态引用），
  而非运行时强制保证。

### 2.2 ModuleLayer（JDK 9+）

每个插件居于独立 `ModuleLayer`（`defineModulesWithOneLoader`），形成真实的每插件模块图。

- 引入：由 modular jar（或 automatic module + manifest）定义层。
- 回收：丢弃层引用；其加载器与类在不可达后可收集。卸载可观察性同样依赖 2.1 的引用纪律，
  且层通过类加载器保留父层引用时不得被外部钉住。
- 隔离：最强——模块封装、同名模块跨插件多版本共存、无意外可见性。
- 成本/风险：纯 JDK 方案中最高——插件必须是 modular jar（或 manifest 正确的 automatic
  module）、解析失败即加载失败、跨模块反射需 `opens`、与 classpath 代码混用麻烦。

### 2.3 OSGi（Core Release 9，Equinox/Felix）

完整模块运行时：bundle 生命周期、`Import-Package` 版本解析、服务注册表，以及 JVM 上最成熟的
类空间管理（停止的 bundle 其类空间被废弃）。

- 引入/回收：一等公民的 bundle start/stop；uninstall + refresh 逐出类空间。
- 隔离：优秀，包级粒度与版本区间。
- 成本/风险：重量级框架，自有编程模型（Activator/DS、自有服务注册表）。其生命周期与服务层
  与 cordis4j 的 fiber/协效应表达高度重复——采用 OSGi 要么两套组合模型并行，要么围绕它重造
  核心，均违背"论文语义的 Java 重想"定位。许可证（EPL-2.0 / Apache-2.0 实现）无碍。

### 2.4 pf4j（3.15.0，活跃维护，Apache-2.0）

轻量插件框架：每插件 `PluginClassLoader`、生命周期（create/start/stop/delete）、扩展点、zip
布局。活跃维护（3.15.0，2026 年 1 月；3.14.1 修复了 zip 路径穿越安全漏洞）。

- 引入/回收：经 `PluginManager` 装卸；3.14.0 起卸载时置空类加载器，正是为协助 GC。
- 隔离：每插件类加载器，无模块封装。
- 成本/风险：中等，但其生命周期与扩展模型与 cordis4j 的 fiber 模型重叠——集成时必须在两者
  之间选一个作真相源；且 pf4j 会给刻意零依赖的项目引入依赖与自身惯例。类卸载仍是纪律，
  同 2.1。

## 3. 对比

| 维度 | 自定义 ClassLoader | ModuleLayer | OSGi | pf4j |
|---|---|---|---|---|
| 运行时引入代码 | 是 | 是 | 是（bundle install/start） | 是 |
| 回收 + GC 逐出 | 靠纪律，可用 PhantomReference 观察 | 层不可达即可收集 | 类空间被废弃（最成熟） | 靠纪律，卸载时置空加载器 |
| 隔离 | 弱（平坦 classpath） | 强（模块图） | 最强（包/版本） | 弱（每插件加载器） |
| 依赖解析 | 手动 | 定义时模块解析 | Import-Package 版本区间 | 极简 |
| 与 cordis4j 的编程模型冲突 | 无（纯 `Plugin`） | 无（`opens` 背后的纯 `Plugin`） | 重（Activator/DS + 自有注册表） | 中（生命周期 + 扩展） |
| 新增运行时依赖 | 无 | 无 | 框架（Equinox/Felix） | org.pf4j:pf4j |
| 工作量 | 低 | 中 | 高 | 中 |

## 4. 推荐

HMR 落地为独立模块 `cordis4j-hmr`，两阶段推进，均不新增运行时依赖：

1. **阶段 1——自定义 ClassLoader 引擎（推荐先做）**：`BytecodePluginLoader`（每插件加载器，
   父为 cordis4j-core 的加载器）+ 以插件路径为键的 `PluginClassRegistry`：装载（jar →
   `Plugin` 实例）、卸载（丢弃引用、以幻影引用观察收集、测试中泄漏即快速失败），以及对接核心
   `Loader` 的 Algorithm 10 形态的桥接：dispose 旧 fiber、实例化重载插件、换入；失败时从备份
   回滚。行为测试（延续 T 系列）必须证明：重载换实例、旧类确实可卸载（幻影引用触发，带 GC
   settle 辅助）、失败重载恢复原集合、插件线程泄漏阻止卸载（记录在案，测试以自身纪律修复）。
2. **阶段 2——ModuleLayer 变体（可选、后置）**：同一注册表接口基于
   `defineModulesWithOneLoader` 实现，服务 modular 插件；仅在跨插件同名版本隔离成为真实需求
   时启用。

不采用 OSGi 与 pf4j：OSGi 以框架级规模重复了 fiber/服务模型；pf4j 与 cordis4j 自有的生命周期
重叠。两者结论记录在此，避免日后重新论证；pf4j 仍是每插件加载器与卸载纪律的有益先例
（其 3.14.0 置空加载器的改动与阶段 1 的回收步骤同构）。

## 5. 已知隔离边界（阶段 1）

阶段 1 引擎（cordis4j-hmr）把每个插件 jar 装入普通 `URLClassLoader`，父加载器为
cordis4j-core 的加载器，即标准父委派：

- **宿主类优先**：宿主 classpath 已提供的类（全部 cordis4j-core、JDK 与应用 classpath 上的一切）
  由宿主加载器解析；插件 jar 内打包的同名类被遮蔽，绝不会从 jar 加载。插件代码因此永远实现
  宿主的 `Plugin` 类型——绝无副本——这正是 T26 的实例身份与 GC 回收保证得以成立的前提。
- **无插件间仲裁**：两个插件 jar 携带同一全限定类时，各自从各自的加载器加载副本
  （每 jar 加载器互为兄弟而非链条），跨插件同名类是不同且不可互换的类型。
- **无依赖版本仲裁**：插件的依赖必须来自宿主 classpath；插件不能自带宿主类的另一版本。
- **无模块封装**：对插件的反射访问不受限制（unnamed module），与 ModuleLayer 的强封装不同。

阶段 1 按设计不支持（见阶段 2 评估 docs/design/hmr-isolation-evaluation.md）：经中间加载器
解决跨插件类冲突、同一依赖的每插件版本、强封装。这些恰是 ModuleLayer 提供的能力，升级路径
已在决策 D27 中预留。

## 6. 参考

- 论文：A Programming Paradigm for Spatiotemporal Composability，§5.2.2，
  https://github.com/cordiverse/paper
- pf4j：https://github.com/pf4j/pf4j （3.15.0，2026-01-27；3.14.1 路径穿越修复）
- OSGi Core Release 9：https://osgi.github.io/osgi/core/framework.introduction.html
- ModuleLayer 机制：https://docs.oracle.com/en/java/javase/21/docs/api/java.lang/java/lang/ModuleLayer.html
