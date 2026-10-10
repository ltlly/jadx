# Git 跨设备交接（2026-10-10）

> **2026-10-10 macOS 复测已完成**：分支 `codex/perf-macos` 在 macOS arm64 上同机 A/B 实测主样本 wall −6.0%、高德 −7.5%、钉钉 −3.1%，峰值 RSS 全部不高于基线，质量门禁零未解释变体；历史 22% 重复处理收益经四个架构逐一 fail-closed 验证在本输出合同下不可收割。详见 [macos-20261010-report.md](macos-20261010-report.md)（含 12 GiB 堆下每次运行一次 ~14.5 s 确定性 G1 Full GC 的环境发现与 16 GiB 处方）。

本文件以 Git 可获取的代码和记录为入口，不依赖原设备的私有说明文件。先读本文件和 [Ponytail full 规范](PONYTAIL.md)，再读下面的报告。当前研究尚未完成，不要把实验分支当成已通过质量验收的补丁。

## 目标与约束

优化现有 Java jadx 的全量 APK→Java 反编译性能。争取千问耗时降低至少 50%，同时保持或降低峰值 RSS；50% 是争取目标，不能通过跳过分析、减少方法体或缩短超时实现。十个固定 APK 已完成六项优化端点的全量对照；后续候选先在千问和独立代表样本筛选，避免反复消耗于十样本扩测。

- 用户允许自主构建、测试、采样、基准、创建分支、commit 和 push；禁止创建 PR。
- 每项改动先说明热点证据、原算法和候选复杂度、全量收益空间及验证成本。较大改造也允许研究，但不能预先保证未经测量的收益。
- 阅读实际流程与全部调用方，优先复用 jadx 和 Java 标准库，不新增依赖、全局缓存或 speculative 框架。保持 Java 11 API 兼容、输出、验证和错误处理；独立优化独立提交。
- 构建、测试、JFR 与正式性能运行串行。A/B 同一 checkout 构建，固定 JDK、heap、8 线程、输入与输出选项，至少三轮交错；报告 wall、CPU、RSS 的中位数和范围。
- 检查退出码、错误、缺失/新增文件、内容和省略体。文件数、语法通过率、历史输出匹配、零查询差异都不能单独证明整体语义等价。
- 遵循 Ponytail full：解决完整任务的最小清晰改动；非平凡逻辑需回归检查；结束时说明未检查项和实质风险。不要干扰另一份 checkout 或无关会话。

## 获取代码与分支

```bash
git clone https://github.com/ltlly/jadx.git
cd jadx
git fetch origin
git switch --track origin/codex/jadx-algorithm-review
```

| 分支 | 提交/用途 | 状态 |
| --- | --- | --- |
| `codex/jadx-algorithm-review` | 六项保留优化 + 最新研究、撤回记录及本交接 | 推荐工作起点；生产源码与 `2655258` 相同 |
| `codex/jadx-performance` | `ab9029f`：六项优化及十 APK 报告 | 已保留结果 |
| `codex/indexed-usage-lists` | `0590b9df011a4dc916d09fc3ba2dfc4ea559d9ec`：使用位置索引 | 实验，未合并 |
| `codex/withdrawn-usage-resolution` | `b321cdaf6bfc6434c15a608a8f36193470928694`：局部复用方法解析 | 已撤回 |
| `codex/reverse-decompile-order` | `56039433d4561133884612d25dd4f8853125dec3`：减少依赖重复处理 | 已撤回 |
| `codex/reuse-live-bitsets` | `e8980feee4dac7667eb04b6093ba3d6ad6cfe424`：存活性 scratch BitSet 复用 | 已撤回 |
| `codex/reverse-live-traversal` | `8a831febbd419f29690bfb3c8b905a8d7c71f5e6`：存活性逆序扫描 | 已撤回 |
| `codex/raw-class-name` | `30061c5cb4c2d8d59bf2ef1d1da2f29fedf8deab`：直接保存原始类名 | 已撤回 |

索引、反转生成顺序及原始类名实验建立在已撤回的 `b321cda` 上。研究独立差异时比较各自父提交；不能把实验分支整条合并进保留分支，也不能把其收益算作六项端点的累计收益。

累计共同正确性基线为 `8e35efc4e156c20d2af40c0a160eb78cdcfe9f40`，它先修复共享三元条件复制污染。六项性能提交依次为：`b6f7f17` outer switch 前驱早停、`6585c3a` CFG 索引循环、`026c993` bottom 候选检查次序、`a021492` 包名集合复用、`5ef1f56` 类型候选按需转换、`2655258` 逐项检查变量使用位置。

## 已有结果与限制

- 六项累计千问 wall 中位数 **88.81→69.05 秒，下降 22.25%**；CPU 下降 18.39%，RSS 下降约 0.29%。十 APK 的逐项范围与内容检查见主报告。尚未达到千问 50% 目标。
- 索引候选 DuckDuckGo **189.12→94.02 秒，下降 50.29%**，CPU 下降 23.44%；千问 **65.35→65.75 秒，无稳定收益，CPU 增加 2.72%**。RSS 中位数两者略增。均为相对直接父候选的独立增量。
- 索引版 core/CLI 共 1,069 项测试零失败、1 项跳过；同一实时列表状态的原线性查找与索引查询共 807,728 次对照零差异。但三处源码变体尚未解释；并发整体行为未被证明等价，未合并。
- 第七项 `b321cda` 曾测得千问独立收益 2.54%，后因 Telegram 子类新增受检异常声明违反父类覆盖合同而撤回。抽取实际声明的 javac 检查 A 成功、B 失败。未证明优化是根因；不得将历史累计 25.62% 作为交付收益。
- 逆序存活性将块扫描减少约 71%，全量 wall 基本不变，撤回。反转生成顺序只有单次约 6.7% 初步收益，却有 2,739 类 token 变化及 6 个新增省略体，未进入正式三轮扩测。

正式反编译运行仍有既有错误并返回退出码 3；没有证明完整 APK 源码可重编译或设备行为等价。保留六项也存在报告中明确披露的质量疑点，不能宣称所有输出已严格等价。

## 当前用户问题与下一步

用户要求深入读代码，解释“使用位置索引为何存在输出差异，从算法上是否严格等价”，并逐项报告各算法原理、实现、通过/未通过/未运行的检查和取舍，由用户决策。此次深入审计尚未完成，交接前只做了代码阅读，没有新增生产改动或实验结论。

优先检查 `0590b9d` 的 `UsageList`、`UsageListTest`、`ListUtils.safeReplace`、`UsageInfo`、`InlineMethods`、`ProcessAnonymous` 及所有读者：

1. 顺序执行、键的 equals/hash 稳定且所有修改经过包装器时，证明 positions 保存首次位置、duplicatePositions 保存全部重复位置的不变量。区分此条件性证明与完整 ArrayList 行为/并发等价。
2. 核查 ClassNode/MethodNode 键的稳定性、所有增删改、继承的 AbstractList 方法及列表发布/别名。`safeReplace` 的 indexOf→set/add 不是原子操作；包装器仅部分操作同步，get/size 未同步。旧路径本身也有跨类无锁写入，不能直接认定索引引入了所有竞态。
3. 分别追踪未解释变体：千问 `com.aliyun.tongyi.livechat.internal.beans.LiveChatInteractionState` 的构造参数、`com.ucpro.chat_native.contentview.newsessionwelcome.WelcomeView` 的 getF110680y/getCoroutineContext 别名；DuckDuckGo `androidx.compose.foundation.text.contextmenu.builder.TextContextMenuBuilderScope` 的构造器/循环变化。输出有差异本身不是索引查错的证据。
4. 顺序控制复现可参考 [ReproduceClassOrder.java](ReproduceClassOrder.java)。受控 API、正常全量 CLI 和带探针的诊断必须分开标注；不得用诊断耗时当正式性能。
5. 把已保留、撤回、只做诊断和未实施算法整理为决策清单，清楚区分质量门未通过、实际测试失败、缺少证明和单纯收益不足。

## Git 内文档入口

- [主报告：六项优化与十 APK 性能/质量](README.md)
- [算法评估：理论收益、DDC/ASC/论文/源码链接、实验取舍](ALGORITHM_REVIEW.md)
- [后续研究、候选撤回与 Telegram 声明检查](CONTINUATION.md)
- [构建、测量、回归和内容比较命令](REPRODUCE.md)
- [APK 版本、来源和 SHA256](corpus.tsv)、[JDK 工具链](toolchains.tsv)、[运行环境](runtime.tsv)
- [六项累计测量](measurements.summary.tsv)、[质量逐项复核](quality-review.json)
- [索引千问测量](indexed-experiment-qwen-measurements.summary.tsv)、[索引 DuckDuckGo 测量](indexed-experiment-ddg-measurements.summary.tsv)
- [索引千问质量差异](indexed-experiment-qwen-quality.details.json)、[索引 DuckDuckGo 质量差异](indexed-experiment-ddg-quality.details.json)
- [Telegram 声明检查脚本](check_telegram_throws.py)、[已记录结果](telegram-throws-signature-check.json)

Git 保存代码、脚本、摘要和审查记录，不包含 APK、冻结分发包、完整输出、JFR 或全部测试日志。另一台设备需要按 corpus 的 SHA256 重新准备输入、固定本机 A/B 的 JDK 并重建，不能声称 clone 即获得历史原始证据。部分 corpus 来源只记录了原设备路径或会变化的下载地址，目前 Git 没有保证其跨设备下载可用性。复现文档含原设备路径，使用时改为新 checkout 和工具链位置，并在新运行中记录环境；不同设备的绝对秒数不宜直接比较。
