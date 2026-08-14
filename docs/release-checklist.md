# Release Checklist — v0.2.0

> 状态：**作者已确认决策（2026-08-14），发布暂缓**。
> 已确认：接受 HMR 边界（配置级热重载，字节码级为 P3）；暂不对外发布（仓库保持 private）；
> 中英双语文档已同步 v2.0。以下发布序列留待日后决定公开发布时执行。

## 1. 就绪状态（已完成，可直接复核）

- [x] 核心：论文核心库语义（§3–§5，Algorithm 1–6）全覆盖（对照表见 README）
- [x] 测试：70 个（core 65 + demo 5），T1–T23 全部通过
- [x] 质量门禁：`mvn verify` 全绿 —— enforcer（JDK 21+）、spotless（google-java-format）、
      JaCoCo 行覆盖 ≥ 85%、javadoc（doclint=all, failOnWarnings）、依赖分析
- [x] 文档：design-contract v2.0 双语（英文规范本 + docs/zh 全量译本）、README 双语、
      CHANGELOG 0.2.0
- [x] Demos：QuickStart / ReactiveComposition / MultiTenant / HotReload / AgentHarness
      （每个都有行为断言的冒烟测试）
- [x] 版本：`${revision}` = 0.2.0-SNAPSHOT（发布时去 SNAPSHOT）
- [x] GitHub 配置：CI workflow、Issue 模板（bug/feature）、PR 模板、dependabot、
      README 徽章、仓库 description/topics

## 2. 已确认的决策（2026-08-14）

- [x] HMR 边界：v0.2.0 的 Loader 是配置级热重载；字节码级热模块替换（论文 §5.2.2）
      明确列为 P3 —— README 覆盖度表已如此声明
- [x] 中文契约译本已同步 v2.0 全量（D1–D20、边界语义 1–22、§7 生命周期）
- [ ] ~~仓库可见性~~：**发布暂缓，仓库保持 private**
- [ ] ~~Maven Central~~：暂缓（发布时需配置 distributionManagement 与凭证）

## 3. 发布序列（公开发布时执行）

1. 仓库转 public（或在 private 下先行 tag）
2. 版本定稿：`${revision}` → `0.2.0`，CHANGELOG 日期核对
3. 最终 `mvn verify` 复跑（SNAPSHOT 移除后）
4. `git tag v0.2.0 && git push origin main --tags`
5. （可选）Maven Central 发布配置与 `mvn deploy`
6. （可选）在 cordis / deepseek-harness 社区按各自规范分享（遵守对方仓库的推广礼仪）
