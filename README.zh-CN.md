# Cordis4j

> 本文档是英文规范本 [README.md](../README.md) 的中文译本；如有出入，以英文版为准。最近同步：2026-08-14。

**Cordis4j** 是 [Cordis](https://github.com/cordiverse/cordis) 元框架在 JVM 上的实现，实现"时空可组合性"：
每次上下文变更都携带被跟踪的逆（时间维），每个依赖都被声明并反应式解析（空间维）。

> 状态：v0.1.0 垂直切片。语义遵循论文
> [A Programming Paradigm for Spatiotemporal Composability](https://github.com/cordiverse/paper)
> 第 3–5 节的形式化模型；API 是 Java 化的重想（inspired-by），不是对 TypeScript 代码的逐行移植。
> 冻结契约与决策日志见 [docs/design-contract.md](design-contract.md)。

## 环境要求

- JDK 21+
- Maven 3.9+

## 模块

- `cordis4j-core` - 零依赖核心库（JPMS 模块 `io.cordis4j.core`）
- `cordis4j-demo` - 端到端垂直切片演示

## 快速开始

参见 `cordis4j-demo/src/main/java/io/cordis4j/demo/QuickStart.java`，然后运行：

```console
mvn install -DskipTests    # 先把 cordis4j-core 安装到本地仓库（只需一次）
mvn -pl cordis4j-demo exec:java
```

预期输出：`alice: hello, hi`，随后是根级计时器数值——`bob` 不产生任何输出，
因为会话 dispose 已逆序撤销其插件与监听器。

## 构建与质量门禁

```console
mvn verify   # enforcer + spotless + 测试（T1-T10）+ jacoco（>= 85%）+ javadoc + 依赖分析
```

## 路线图

- **P2** - 声明式依赖（inject）+ 论文 Algorithm 3/5 的完整"provider 卸载前排空 dependents"顺序、
  基于虚拟线程的惯性生命周期状态机、注解注入、事件过滤器、配置级热重载。
- **P3** - 字节码级热模块替换（自定义 ClassLoader / ModuleLayer 方案评估，参考 OSGi 与 pf4j 先例），
  以及生态集成（Spring、Quarkus、LangChain4j）。

## 致谢

Cordis4j 的语义基于 Cordis 论文
（[github.com/cordiverse/paper](https://github.com/cordiverse/paper)）与参考实现
[cordiverse/cordis](https://github.com/cordiverse/cordis) 和 `@deepseek-ai/cordis`
（代码均为 MIT 许可）。Cordis4j 本身以 [MIT 许可](LICENSE) 发布。
