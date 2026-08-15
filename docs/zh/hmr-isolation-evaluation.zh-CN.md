# HMR 隔离升级：父委派 vs child-first vs ModuleLayer

> 本文档是英文规范本 [../design/hmr-isolation-evaluation.md](../design/hmr-isolation-evaluation.md)
> 的中文译本（规范本语言：英文）。如有歧义，以英文版为准。最近同步：2026-08-15（v1 初版）。
> 状态：评估（核验项 P2）。本文权衡把 cordis4j-hmr 阶段 1 的隔离（父委派 URLClassLoader，见
> docs/design/hmr-evaluation.md 第 5 节）升级为 child-first 加载器或 ModuleLayer 的成本与收益。
> 结论汇入决策 D27；仅在真实需求出现时再动代码。

## 1. 评估对象

阶段 1 把每个插件 jar 装入父为 cordis4j-core 加载器的 `URLClassLoader`。其已文档化边界：宿主
类优先（插件内同名类被遮蔽）、插件不能自带宿主依赖的其他版本、无模块封装。问题是：是否升级、
何时升级。

## 2. 候选升级

| 维度 | 阶段 1（父委派） | child-first 加载器 | ModuleLayer |
|---|---|---|---|
| 宿主类型身份 | 保证（插件永远看到宿主 `Plugin`） | 须把 cordis4j-core 包排除在 child-first 之外 | modular 插件 `require` 核心模块——身份保持 |
| 插件自带依赖版本 | 否 | 是（排除包之外） | 是（每层模块图） |
| 跨插件同名类 | 各持副本、不可互换 | 各持副本 | 各模块独立；封装防止意外混用 |
| 强封装 / 反射隔离 | 无 | 无 | 有（`exports`/`opens`） |
| 插件 jar 约束 | 无 | 无 | modular jar 或正确的 automatic-module manifest；解析失败即整层失败 |
| GC 回收保证（T26） | 已验证 | 不变（加载器可达性） | 不变，且层不得被外部钉住 |
| 实现成本 | 已交付 | 中（自定义加载器、排除清单、测试） | 高（模块图装配、反射面、classpath/module-path 混合） |

## 3. 需求场景

- **插件需要宿主也在用的库的自身版本**：阶段 1 强制宿主版本；child-first + cordis4j-core
  排除以低成本覆盖；ModuleLayer 以更强保证覆盖。
- **两个插件打包同一库类的不同语义**：两种升级都保持副本分离；只有 ModuleLayer 防止跨插件
  类型意外混用。
- **不受信插件需要拘束**：只有 ModuleLayer 提供封装；在现代 JDK 上这也是 Security Manager
  故事的继承者。

cordis4j 自身模块与 demo 目前都不需要这些场景。

## 4. 建议

维持阶段 1（父委派）为交付默认；其边界已文档化并被 T34 钉住。升级路径：

1. **带排除清单的 child-first**（插件确实需要打包依赖时的首选）：把 `io.cordis4j.core` 与
   `java.*` 排除在 child-first 之外，插件代码保持宿主类型身份；close-and-collect 回收不变。
2. **ModuleLayer**（需要封装或每插件模块图时）：即
   docs/design/hmr-evaluation.md 第 4 节已勾勒的阶段 2 引擎。

决策与其触发条件已记录于设计契约（决策 D27）；现在不动代码。
