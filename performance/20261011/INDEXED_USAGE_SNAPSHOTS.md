# 使用关系快照：位置索引与路径复制

本轮以使用关系并发修复 `0bf6c9d67dbb65ead37476337a95996a7431dc3a` 为基线，研究大量使用关系替换时的查找与复制成本。候选只改变 ClassNode 的 `useIn` / `useInMth` 和 FieldNode 的 `useIn` 表示；保留所有反编译 pass、验证及原有使用关系更新调用。MethodNode 的反向引用维护保持原实现。

**决定：未通过输出质量验收，独立保存实验，保留分支恢复基线。** DDG 和千问各三对正式测量、基线节点回归和候选完整检查均已完成。DDG wall 中位数下降 80.29%，千问 +0.33%；候选出现未在九个已观察基线中见到的非法 Java 形式，不能作为已交付提速。实验代码和测试保存于 `codex/usage-snapshot-tree-experiment` 的 [`21bfa89d`](https://github.com/ltlly/jadx/commit/21bfa89d62f4232d97a798422907f6bfc0721385)。`codex/dependency-recovery-research` 的生产源码恢复为 `52fef6e`（与 `0bf6c9d` 的生产实现一致），安装目录全部 62 文件也与冻结 A 相同。

历史使用关系优化的约 50% 收益不属于本轮结果；本轮也没有重新实施 IR 驻留或 CFG 查询候选。本文对应的可移植数值、质量审阅摘要和本机证据 SHA256 见 [indexed-usage-snapshots-results.json](indexed-usage-snapshots-results.json)。原始 APK 输出、JFR 和逐轮记录保留在本机 `out/jadx-perf/20261011/usage-snapshot-tree`，未提交到 Git。

## 基线成本与本轮热点证据

`0bf6c9d` 为每次读取提供稳定只读快照。其 ClassNode.replaceUsage 在 root 使用关系锁内分别复制两个 ArrayList，再调用 `ListUtils.safeReplace` 线性查找第一处匹配；FieldNode.replaceUseIn 对一个列表执行同样的操作。没有匹配时追加，已有重复项保留。对长度约为 L 的列表连续更新 R 次，复制与查找合计 O(RL)；长度变化时应写为 O(Σ Lᵣ)。这项成本同时占用共享更新锁。

本轮重新运行的完整 DuckDuckGo 5.297.1 基线 JFR 位于本机 [`usage-snapshot-tree/ddg-baseline-profile`](../../out/jadx-perf/20261011/usage-snapshot-tree/ddg-baseline-profile)。[`metadata.json`](../../out/jadx-perf/20261011/usage-snapshot-tree/ddg-baseline-profile/metadata.json) 记录 JDK 27、`-Xms256m -Xmx16g`、`ParallelGCThreads=3`、8 线程、完整 Java 输出及 `--no-res`，启用 JFR `profile`、栈深 256，未启用依赖恢复探针。输入 APK SHA256 为 `4a995d765d2f0ace0fbdfdf925f24219679b5741e4a05728dc3cad15411331e2`；使用冻结的 `inline-usage-fix/fixed-distribution`，core JAR SHA256 为 `f550a2e2fc32ebf2dbe54fb923f23dc39eded638dcd933eaff7e4486360051e5`。

[`metrics.json`](../../out/jadx-perf/20261011/usage-snapshot-tree/ddg-baseline-profile/metrics.json) 记录 wall **476.21 秒**、进程 CPU **845.94 秒**（user 828.36 + sys 17.58）、peak RSS **17136488 KiB**、退出 **3**、**31108** 个 Java 文件；[`diagnostics.txt`](../../out/jadx-perf/20261011/usage-snapshot-tree/ddg-baseline-profile/diagnostics.txt) 记录 **520** 个错误。退出 3 不代表反编译无错误。

[`profile.summary.json`](../../out/jadx-perf/20261011/usage-snapshot-tree/ddg-baseline-profile/profile.summary.json) 共 **48685** 个执行样本，其中 **159** 个栈截断。以下为 inclusive 栈出现计数，分母是全部执行样本：

| 更新入口 | 样本数 | 占比 |
| --- | ---: | ---: |
| ClassNode.replaceUsage | 19481 | 40.0% |
| FieldNode.replaceUseIn | 5454 | 11.2% |
| MethodNode.removeUseIn | 2677 | 5.5% |
| MethodNode.publishUseIn | 2633 | 5.4% |

inclusive 项之间可能重叠，不能相加后当作可消除的 wall 时间。MethodNode 两项用于说明剩余热点，不属于此候选的修改范围。

按首个 jadx 调用者归属的分配采样字节权重为：ClassNode.replaceUsage **619478626616**、ListUtils.safeReplace **398937570728**、FieldNode.replaceUseIn **147652739032**。它们是 JFR 采样权重，不能当作精确总分配、峰值内存或候选将节省的字节数。safeReplace 桶也没有在这里进一步分摊给各个节点。

上层 [`ddg-baseline-profile-command.json`](../../out/jadx-perf/20261011/usage-snapshot-tree/ddg-baseline-profile-command.json) 的 cwd 为先前归档的 `type-update-small-map/withdrawn`，与仓库根不同；驱动、measure.py、输入 APK、分发包、输出目录，以及实际 Java 命令的 classpath/JFR 路径均为绝对路径。该轮实际加载上述冻结分发包。这个 cwd 差异与 JFR 开销一并保留在证据中；本轮是热点诊断，**不计入正式 A/B 性能结果**。

## 候选实现

实验新增 [`UsageInfoList`](https://github.com/ltlly/jadx/blob/21bfa89d62f4232d97a798422907f6bfc0721385/jadx-core/src/main/java/jadx/core/dex/nodes/utils/UsageInfoList.java)，由每个节点、每个使用列表独立持有。完整 setter 仍先调用既有 `ListUtils.immutableCopy` 脱离输入。列表长度达到 **256** 后，首次合适的 replace 才建立位置索引与不可变的 32 分支树；小列表沿用 ArrayList 复制。

- 位置索引用 HashMap 保存每个相等值的第一处位置；只有出现重复项时，另用 TreeSet 保存这一相等值的全部位置。查询和移除始终针对第一处匹配。
- 树的叶子按原列表位置保存元素。替换或已索引列表的追加只复制根到目标叶子的路径，每层最多复制 32 个引用；其余分支与旧快照共享。树节点数组不再原地修改。
- 对外仍返回不可修改的 List；可变位置索引不向读者暴露。旧快照只持有自己的树或原数组，不持有 owner 或索引，列表结构与元素引用不会随着后续使用关系更新改变；节点对象本身仍可变。
- 完整 setter 发布新的 owner 并丢弃旧索引。尚未索引的 add 仍复制 ArrayList；remove 仍复制、删除第一处匹配，再建立未索引的 owner。它们不会为了优化而改变重复项或更新顺序。

使用标准库 HashMap、TreeSet 和现有列表帮助方法，没有新增依赖或跨 root 的缓存。

## 保留的行为与调用方边界

**锁与发布。** 增量更新继续在 `RootNode.getUsageInfoLock()` 下读取当前状态、更新并发布；setter 仍在锁外复制输入、锁内替换 owner。owner 引用与其中当前 List 均以 volatile 发布。读者取得的单份 List 稳定，但跨多个 getter 的读取、整个 InlineMethods.updateUsageInfo 仍不是一个原子事务。持有使用关系锁时不新增类处理锁、解析或反编译调用。

**顺序、重复与 null。** replace 保留 `safeReplace` 的首项替换/未命中追加语义；不会去重或重新排序。相等但非同一个对象的新节点仍真正替换目标槽位，不能因 `old.equals(new)` 而跳过。所有列表继续允许 null；FieldNode 的 null 替换/追加合法。ClassNode.replaceUsage 仍先发布方法列表，再读取 old/new 方法的 parent 更新类列表，因此 parent 解引用抛出 NullPointerException 时，方法列表已发布的前缀行为保持不变。

**相等性与异常。** 索引只接受 null 或与配置 elementClass 完全一致的节点对象。ClassNode 的 key 由固定 ClassInfo 类型标识决定；MethodNode 由固定 MethodInfo 的声明类及 shortId 决定，别名、推断类型和方法体卸载不改变这些 key。输入列表或查询/替换值出现未知子类时，退回原来的线性 `query.equals(element)` 路径，不对未知子类调用 hashCode。树快照先准备完成，再修改索引、最后发布；索引更新失败时清除可变索引并重新抛出，已发布列表保持可用，后续更新可重建索引。这不保证与旧实现相同的内存耗尽时机。

**节点生命周期。** 普通 unload 保留使用关系及所属索引。完整 setter 换代时不合并旧索引；深重载重建方法/字段节点，再从 usage 数据恢复列表。索引不按 MethodInfo/FieldInfo 跨实例共享。移除某值的首项后，索引中的代表 key 重绑到当前列表实际剩余的节点，避免 HashMap 因相等 key 沿用旧对象而额外保留已移除 MethodNode。读者持有的旧快照仍可保留其原有节点，这是快照契约本身。

**调用方。** 已检查 UsageInfo 的串行/并行发布与 applyForClass、GUI UsageData 恢复、InlineMethods、ProcessAnonymous、常量恢复和注解中的字段追加，以及 ClassGen/Java API 的读取。GUI 方法解析列表可能含 null；KotlinMetadataUtils 的 companion 判定使用字段列表的大小与首项；ProcessAnonymous 使用大小、遍历、contains/containsAll。这些读取继续观察原有顺序和多重性。JavaClass/JavaField 公开 API 仍转换为独立列表；未发现仓内调用者要求具体 ArrayList 类型或原地修改 getter 列表。

FieldNode 的更新仍只维护自身列表；InlineMethods 随后独立更新所属 ClassNode。MethodNode 的删除、合并、反向关系及通知继续使用现有实现，避免把位置位移和反向通知混入此次表示变更。

## 复杂度与内存代价

令 L 为当前列表长度，R 为同一索引代际内的替换次数，D 为 `max(2, 同一相等值的最大重复数)`。下面假定标准 HashMap 的平均常数查找；哈希冲突会增加索引成本，这些不是针对任意 key 的最坏情况上界。

| 操作 | 基线 | 候选 |
| --- | --- | --- |
| 首次建立索引与树 | 无 | O(L log D)；初始 UsageSet 唯一列表通常为 O(L) |
| 索引建立后的 replace | O(L) 查找与复制 | O(log₃₂ L + log D) |
| 已索引列表 add | O(L) 复制 | O(log₃₂ L + log D) |
| 未索引 add、remove | O(L) | O(L)，保留复制路径 |
| 完整 setter | O(L) | O(L)，重置索引 |
| 单次 get(index) | O(1) | 树快照 O(log₃₂ L)，数组快照 O(1) |
| 整表顺序遍历 | O(L) | O(L)，迭代器缓存分支路径 |

连续替换从 O(RL) 变为一次 O(L log D) 建立加 O(R(log₃₂ L + log D)) 更新。remove、完整 setter 或未知子类回退可能结束该代际，之后重新建表的成本需要另计。List 的 contains/indexOf 等读取仍采用列表相等性查找，不能把它们算作索引加速。

当前索引和树需要 O(L) 驻留空间；每次路径复制分配 O(log₃₂ L) 个固定宽度数组。旧快照共享未修改分支，其实际保留量取决于读者持有多少版本。索引驻留、首次构建、读取路径及共享锁中的工作量都可能抵消部分收益；分配采样权重不能代替 peak RSS 或正式 wall 测量。

## 回归记录

实验版 [`InlineMethodsUsageTest`](https://github.com/ltlly/jadx/blob/21bfa89d62f4232d97a798422907f6bfc0721385/jadx-core/src/test/java/jadx/core/dex/visitors/InlineMethodsUsageTest.java) 在既有六项基础上新增两项：两个线程对 512 个重复元素执行 ClassNode 与 FieldNode 替换，核对旧快照及全部 200 次更新；ClassNode null replacement 核对方法列表先发布而类列表不变的异常前缀。

这 **8 项节点回归已经在基线生产实现上实跑通过**：0 failures、0 errors、0 skipped，Gradle 退出 0。记录见 [`baseline-node-regression-summary.json`](../../out/jadx-perf/20261011/usage-snapshot-tree/baseline-node-regression-summary.json)、[`baseline-node-regression.xml`](../../out/jadx-perf/20261011/usage-snapshot-tree/baseline-node-regression.xml) 和 [`baseline-node-regression-retry-exit.json`](../../out/jadx-perf/20261011/usage-snapshot-tree/baseline-node-regression-retry-exit.json)。首次命令误用归档 cwd，`./gradlew` 在启动前报 FileNotFoundError；随后在仓库根重试通过，两个命令与退出记录均保留。基线检查结束后 ClassNode/FieldNode 候选源码已恢复，记录于 `baseline-node-source-restoration.json`。

新增 [`UsageInfoListTest`](https://github.com/ltlly/jadx/blob/21bfa89d62f4232d97a798422907f6bfc0721385/jadx-core/src/test/java/jadx/core/dex/nodes/utils/UsageInfoListTest.java) 的 8 项 helper 检查覆盖：输入脱离与旧快照、首项重复和相等对象身份/哈希冲突、32/1024/32768 树边界及反向迭代、单项/重复桶转换、固定随机序列对照 ArrayList、完整 List 读取及禁止修改、未知子类回退、索引更新失败后的重建。这 8 项属于候选完整检查，不能与上述基线上的 8 项节点测试混同。

候选完整检查已完成：core/CLI **1143 项测试，0 failures、0 errors、1 个既有条件跳过**；格式、主/测试 Checkstyle 与 installDist 全部通过，Gradle 退出 0。Gradle 用 JDK 25 启动、构建/测试指定 Java 11；[命令](../../out/jadx-perf/20261011/usage-snapshot-tree/candidate-full-checks-command.json)、[退出记录](../../out/jadx-perf/20261011/usage-snapshot-tree/candidate-full-checks-exit.json)、[汇总](../../out/jadx-perf/20261011/usage-snapshot-tree/candidate-test-summary.json) 和 [XML 归档](../../out/jadx-perf/20261011/usage-snapshot-tree/candidate-test-results.zip) 均保留。

## 冻结候选

生产代码及测试的 [candidate.patch](../../out/jadx-perf/20261011/usage-snapshot-tree/candidate.patch) SHA256 为 `15fa599c70bc2c7a77ce4cf70a9f1ffa246188df8f9d5a35322011dec5ec1d8e`，逐文件源码哈希见 [candidate-source-hashes.json](../../out/jadx-perf/20261011/usage-snapshot-tree/candidate-source-hashes.json)。A 使用已冻结的 usage 修复分发包，B 使用本轮 `candidate-distribution`；[distributions.json](../../out/jadx-perf/20261011/usage-snapshot-tree/distributions.json) 绑定各 62 个文件，仅 core JAR 不同。A/B core JAR SHA256 分别为 `f550a2e2fc32ebf2dbe54fb923f23dc39eded638dcd933eaff7e4486360051e5` / `3419e58070c3ec55a21597d433a6dc95d353c013af69ccbe6b1fca31ec5fe944`。

## 正式性能

两个固定 APK 分别按 A1、B1、A2、B2、A3、B3 串行交错运行。JDK 27、`-Xms256m -Xmx16g`、`ParallelGCThreads=3`、8 线程、`--no-res`、locale、输入和其余 61 个分发文件均相同。正式运行未启用 JFR、依赖探针或位置影子检查；输出哈希和审计在 Java 计时结束后进行。退出码、命令、分发包/输入 SHA256、文件 manifest、host-before 与每 5 秒主机 CPU 记录均保留。

| APK | wall 中位数 A → B | 变化 | 进程 CPU 中位数 A → B | 变化 | peak RSS 中位数 A → B（KiB） | 变化 |
| --- | --- | ---: | --- | ---: | --- | ---: |
| DuckDuckGo 5.297.1 | 425.44 → 83.85 s | −80.29% | 787.22 → 436.43 s | −44.56% | 17072612 → 12665608 | −25.81% |
| 千问 7.3.6 | 124.11 → 124.52 s | +0.33% | 734.40 → 739.07 s | +0.64% | 16168704 → 16151792 | −0.10% |

DDG wall 范围 A 417.74–460.87、B 81.27–87.68 秒；RSS 范围 A 17041360–17158264、B 12598612–13179140 KiB。千问 wall 范围 A 123.25–126.23、B 123.68–124.93 秒；RSS 范围 A 16162456–17061492、B 16039112–16605540 KiB。千问没有显示明确提速，50% 目标尚未达到。

DDG A2 期间发生一次只读历史报告搜索，遍历了较广的原始记录目录，约 16.5 秒后 Ctrl-C 终止。没有启动其他 JVM、构建或测试，也没有扫描生成源码；仍可能造成 CPU/I/O 干扰。[host-activity-A2.json](../../out/jadx-perf/20261011/usage-snapshot-tree/host-activity-A2.json) 保存命令及 UTC 时间，不能据此声称主机完全空闲。A1/A3 也分别得到约 460.87/417.74 秒的基线时间，各自 B 轮均低于 88 秒。

逐轮数值与全部控制断言见本机 [DDG summary](../../out/jadx-perf/20261011/usage-snapshot-tree/duckduckgo-5.297.1-summary.json) 和 [千问 summary](../../out/jadx-perf/20261011/usage-snapshot-tree/qwen-7.3.6-summary.json)。这两个 summary 只读取完整 manifest 中变化文件的必要版本比较 Java token；没有把嵌套 JFR 样本计数当成 wall 收益。

## 输出质量记录

**输出质量未通过。** 全部十二轮退出 3。DDG 每轮 31108 个 Java 文件，千问每轮 71047 个，文件集没有缺失或新增。DDG 累计错误 A=520/520/520，B=521/521/520；千问 A=1032/1032/1033，B=1032/1032/1032。累计错误事件数不等于输出中独立失败方法数。

DDG 六轮的 1480 个占位方法集合完全相同。六轮合计 37 个文件字节变化，25 个类有 token 变体；第一对两份 protobuf 的循环清理位置和 Rx 的 unlock 异常路径有实际语义差别。对应 B1 整类文件在本轮未修改 A2 中逐字节出现，A3 也出现；这证明当前冻结基线存在这些结构，不能证明其中任一结构正确或出现概率不变。

DDG 有 5 个候选整类变体不在当前 A1/A2/A3 并集中：AppLocalesStorageHelper 和 MultiInstanceInvalidationService 只删除纯 catch/rethrow 包装；ProfilingRequestBuilder 的抽象 getter 改名与四个既有直接具体子类一致，改善了这个五类组内的 override 一致性。NavigationEventInput 的 B1 protected 声明与 DEX 一致，四个已列出的调用符合访问规则；冻结 A 的独立 API 生成也出现 B1 整类 token。RealBufferedSink B3 的 emit 缺 throws IOException，却无 catch 地调用同轮 Sink.write，存在局部 Java checked-exception 编译缺口；指定基线/API 观察未复现这一缺失声明。详细证据见本机 [首对审阅](../../out/jadx-perf/20261011/usage-snapshot-tree/ddg-first-pair-quality-review.md)、[六轮审阅](../../out/jadx-perf/20261011/usage-snapshot-tree/ddg-six-run-quality-review.md) 和 [目标 DEX/源码审阅](../../out/jadx-perf/20261011/usage-snapshot-tree/target-source-quality-review.md)。

千问六轮合计 188 个文件字节变化、158 个类有 token 变体；A 占位方法数 911/907/914，B 904/903/909。B2 的 StrategyInfoHolder.saveData 和 B3 的 HttpDns 两个缓存方法、RecommendConfig.ULiangData.DataFetchTask.run 共 4 个标识不在当前三轮 A 并集中；四项及对应三个整类文件均与同一冻结 0bf6 包的历史 inline-usage-fix/qwen-B1 逐字节相同。

千问 B 有 58 个类的 60 个不同 token 变体未见于当前三轮 A。限定到这 58 条路径，再与六个相同冻结分发包、输入和运行选项的历史基线比较，39 个变体得到整类逐字节证据；其余 21 个变体逐项审阅，分类为 7 个纯 rethrow 包装、1 个带类型的 null、8 个其他异常声明变化、1 个 continuation getter 别名变化，以及以下四个需要阻止验收的源码问题：

- B3 `TitleBarMenuConfigManager` 构造器出现五处 `0 == true ? 1 : 0`，int 与 boolean 比较是非法 Java。
- B2 `AMapUtils.a.run()` 在明确继承 `Thread` 的类中新增受检异常声明，违反 `Thread/Runnable.run()` 的覆盖合同。这是源码规则检查，未运行 javac。
- B1/B3 `UCDataFlow.run()` 由占位体变为约 1300 行输出，但含 `?? r10;` 等非法语法；占位数下降不能当作质量提升，未审完这个大方法的语义。
- B2 `AbstractDao.deleteInTxInternal` 改变正常 unlock 的异常清理范围；A 另有未声明变量，B 修复了这一局部问题。是否忠于原始异常范围尚未核对 DEX，不能判定整个变体等价。

前两种非法形式均未见于当前三轮加六个指定历史基线；这说明本轮没有足够证据通过质量门槛，**不证明索引实现造成这些形式或提高了出现频率**。完整分类及源码/manifest 哈希见本机 [千问逐项审阅](../../out/jadx-perf/20261011/usage-snapshot-tree/qwen-quality-review.md) 和结构化结果中的 21 条摘要。

## 独立诊断

位置影子脚本先临时编译诊断 helper、冻结诊断包，再恢复候选源码并重建核对正式 B 的全部 62 文件，之后才运行 APK。每次索引查询在同一 root 使用关系锁内，与同一不可变快照的 List.indexOf 比较第一位置，保留查询次数、列表长度和、不匹配数。

- 千问完整诊断完成：**53119 次查询，列表长度和 76722552，0 次位置不匹配**。它不能证明整个列表更新、并发顺序或生成输出语义等价。
- DDG 影子诊断在 **900 秒超时**，驱动退出 1，进程组已清理；没有完整输出 manifest、最终查询计数或最终错误总数，因此不报告“DDG 零差异”。逐次恢复线性查询代价很大，诊断耗时不计入正式中位数。
- 随后的冻结 A DDG `--log-level error` 运行因前一步失败而未启动；正式 B1/B2 的额外 1 个错误事件未定位到具体事件。错误计数/去重机制只是已检查的可能因素，不能当作本轮原因。

本机 [错误事件诊断](../../out/jadx-perf/20261011/usage-snapshot-tree/error-event-review.md) 对完整千问诊断日志解析得到 1033 个计数事件，与最终 report 和 CLI 两处总数一致；650 个唯一节点不能替代事件总数，另有 9 条不计入该计数器的 ERROR。DDG 超时前观察到 520 个事件，但没有最终报告可校验，因此只是部分日志计数。这些记录未识别正式 DDG B1/B2 的额外事件。

冻结 A 的独立 API 生成同时保留五个目标类与七份 smali。两份 protobuf StreamDecoder 的 DEX 正常清理在循环之后；A1/B2 输出符合这一相对位置，A2/A3/B1/B3 把清理放进循环体内的 finally，成功 skip 后也重复清理，存在真实语义问题。两边各有两次这种错误观察，基线复现不能替代正确性证明。对 RealBufferedSink 的源码阅读发现异常推断属性与方法体卸载顺序的可疑路径，对 finally 合并也发现顺序敏感候选；均未通过回归或实际执行轨迹确认，未实施修复。

诊断、API 检查和影子脚本的自检均与正式性能分开存档。第一次目标检查在 javac 前因驱动缺少 JAVA_HOME 失败，补齐环境后重试退出 0，两次记录均保留。

## 最终保存状态与未检查项

独立实验提交 `21bfa89d` 的五份 Java 文件逐项 SHA256 与正式测量时源码相同。实验代码未合并到保留分支；本轮没有新增已验收性能改动，也没有达到千问 50% 目标。完成全部运行后，已在 `52fef6e` 源码下重新执行 installDist，退出 0，核对全部 62 个文件与冻结 A 一致，见本机 [最终恢复证明](../../out/jadx-perf/20261011/usage-snapshot-tree/baseline-final-restoration.json)。候选构建/检查结果仍只适用于实验提交。

后续若继续这一方向，应先把非法表达式、异常声明和 finally 顺序变化收敛为可重复的生命周期/顺序回归，再评价此表示变更是否可接受；不能仅以位置查询零差异或旧版本曾出现变体而合并。

未完成 GUI/外部插件、任意并发深重载、整份输出重编译或设备执行验证；其余八类异常签名与 continuation 别名的跨类合同也未全部核对。候选新增索引驻留量和快照共享量依赖调用分布；APK 全量输出中仍有已证实的非法 Java 与 finally 语义问题，根因尚未闭合。
