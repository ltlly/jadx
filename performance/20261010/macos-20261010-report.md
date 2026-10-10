# macOS 复测报告：jadx 全量反编译性能（2026-10-10）

分支：`codex/perf-macos`（自 `codex/jadx-algorithm-review` / 生产端点 `2655258` 切出）。
基线 A = `6453903f`（与 `2655258` 生产源码零 diff，仅多 docs）；优化端 B = `23c2ffc7`。
本报告为 [HANDOFF.md](HANDOFF.md) 的跨设备续篇；所有原始运行输出（metrics JSONL/TSV、审计 JSON、GC 日志、JFR 录制）保存在测量机 scratch 目录，未入库。

## 1. 结果摘要（同机 A/B，直接首尾比较，不相加增量）

| 样本 | A wall 中位 | B wall 中位 | Δwall | A RSS 中位 | B RSS 中位 | ΔRSS | 文件集 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 千问 7.2.1（主样本，121 MB） | 70.08 s（69.78–71.34） | 65.87 s（65.68–66.89） | **−6.0%** | 8.23 GiB | 8.10 GiB | −1.6% | 一致（70,412） |
| 高德地图 16.23.0（167 MB） | 21.00 s（20.95–21.04） | 19.41 s（19.34–19.49） | **−7.5%** | 8.47 GiB | 8.24 GiB | −2.6% | 一致（42,136） |
| 钉钉 8.5.1（318 MB） | 114.81 s（109.33–120.30） | 111.27 s（108.15–114.39） | **−3.1%** | 8.60 GiB | 8.44 GiB | −1.8% | 一致（88,618） |

- CPU（user+sys）中位：主样本 A 334.9 s → B 346.2 s（+3.4%，并行前端以 CPU 换 wall）；高德 +3.1%；钉钉 +0.7%。
- 退出码全部为 3（与基线既有行为一致，错误计数波动 1011–1014 属 jadx 本身非确定性）；错误/警告行集合归一化后 A/B 完全一致。
- 每轮配对差（主样本）：−5.9% / −6.0% / −6.2%，方向完全一致。
- 峰值 RSS 中位数三样本全部 B ≤ A，满足不回退门禁。

**≥30% 目标未达成**；实测同机上限约 3–7.5%，原因见 §4（历史 22% 重复处理收益被输出等价合同阻断，逐架构 fail-closed 已归档）。

## 2. 环境与语料（SHA256 记录在案）

- 测量机：macOS arm64，14 核（10 性能核），24 GiB；测量 JDK 固定 `/opt/homebrew/opt/openjdk@21`（21.0.12.1）；构建/测试工具链 JDK 11（foojay 自动供给）；Gradle 9.7.1。
- 冻结 JVM 参数（A/B 完全一致）：`-Xms16g -Xmx16g`；launcher 默认 `DEFAULT_JVM_OPTS` 恒定（`-Xms256M -XX:MaxRAMPercentage=70.0 -XX:ParallelGCThreads=3` 等，`-Xms/-Xmx` 显式覆盖）。
- jadx CLI：`--no-res --threads-count 8 -d <out>`；计时工具 `measure_macos.py`（`/usr/bin/time -l` 取 wall/CPU/峰值 RSS，metrics schema 与历史一致）；每轮前后记录 load average。
- 串行协议：每 campaign 先 1 轮弃置 warm-up（首跑冷启动显著偏慢），再 A/B 严格交错。
- 语料（本地替代集，历史 corpus 不可跨设备复用）：
  - `com.aliyun.tongyi_7.2.1.3009.apk`（主样本）SHA256 `586651f1010008a59e216784032633d1b308b93cf4869f5e0f6293fd2eddb1ef`，121,203,197 B —— 与历史 `qwen-7.3.6` 同包不同版本。
  - `com.autonavi.minimap_16.23.0.2005.apk` SHA256 `377345fc58569295c16a826ccfea8439aa6fdd865d438ef3e33e6ff851cb1fec`，167,577,252 B。
  - `com.alibaba.android.rimet_8.5.1.apk` SHA256 `aa6cdd1db5188e3ea8f11aa8c561445bbff7861ee36fd2a5d1828a4772c24faa`，318,227,228 B。

### 2.1 GC 稳定化（重要环境发现）

12 GiB 堆下**每次全量运行都含一次确定性 ~14.5 s G1 Full GC**（G1 Compaction Pause，12.2 GB→8.5 GB，约第 75–85 s），是此前 84–122 s 波动的主要来源；`-XX:G1HeapRegionSize=32m` 不消除但使时序高度稳定；ParallelGC 严重回退（199 s，17 次 Full GC）。`-Xms16g -Xmx16g` 完全消除（0 次 Full GC），wall 降至 ~70–72 s，对 A/B 同等适用后冻结。跨设备复测时应检查自身 GC 日志的 Full GC 签名。

## 3. 保留的优化（独立提交，全部含回归测试）

| 提交 | 内容 | 证据/机制 |
| --- | --- | --- |
| `b8b413d9` | macOS 测量与审计工具（measure/compare/audit/quality 的 mac 入口，schema 不变） | 复测基础设施 |
| `115b9792` | **候选 1**：usage-info 前端并行化（按类分片收集 + 确定性归并 + 并行排序/物化，七轮 setter 串行发布逐字保留；kill switch `-Djadx.usage.parallel=false`） | 启动串行段 ~7.6% 采样份额；JFR 主导收益来源；14 个新测试 + 真实 DEX 串/并全量 published-state 等价对照 |
| `e714808b` | DEX reader 声明 `supportsParallelClassScan()`（additive 接口，隔离性已验证：类独占 `DexCodeReader`、私有 ByteBuffer 游标） | 激活候选 1 并行收集 |
| `90150b01` | 候选 1 加固：oversized-shard frontier 顺序 + chunk 失败前缀语义 | RCA 发现的两个真实缺陷；4 个测试修复前全部失败（已留证） |
| `87e996f6` | MethodUtils 外部类 overload 候选桶索引（JFR 2.84% 热点；有界 + kill switch） | 等价性：桶内仍按原序执行 `isOverloadedBy` |
| `fb33a13a` | ArgType plain ObjectType 有界 intern（JFR 2.83%；4096 槽 direct-mapped） | 缓存值与新建值 equals/hash/getClass 全同 |
| `b4257ef5`+`6bfd0717` | 依赖摘要基础设施 v1+2a（**默认关闭**，`-Djadx.deps.summary` 显式开启才生效） | 认证覆盖 f≈0.13，wall 中性偏负；保留为合同加固/T8 修复/测试资产，不计收益 |
| `07162715` | DepthRegionTraversal 冗余 else 重扫改 DFS 工作集（O(K×R)→局部工作集；最坏方法 21.1M→~31k 次访问，948→77 ms） | 与旧算法快照逐字节一致的链式 fixture；`JavaBeanDeserializer` 输出 byte-identical；仅 `REMOVE_REDUNDANT_ELSE_VISITOR` 切换 |
| `23c2ffc7` | spotless/checkstyle 合规 | 门禁全绿 |

质量审计（最终 campaign）：文件集 6/6 组 missing=extra=0；B 侧省略体每轮 892/884/887 ≤ A 侧 894/885/888，超出 A 并集的仅 3 个单轮瞬时事件（无稳定丢失）；错误/警告行归一化后零新增；token 变体 union 156 文件全部归入已知基线抖动族（finally/try-catch 作用域、import 顺序、kotlin `copy$default` 内联、throws 签名、匿名类 IR 竞态、等价重命名、注释行号），**未解释变体 = 0**。次级样本：钉钉 1,688 changed 中 token 级仅 149（0.17%，与主样本噪声带同级），且 B 消除了 A 的 2 处 `ConcurrentModificationException` 抖动；高德 error_changes=0。

JFR 证据：B 侧全量运行录制（`B-final.jfr`）显示 `MethodUtils.processMethodArgsOverloaded` 份额 2.84%→2.04%、UsageInfo 前端帧退出 top-25，与候选 1 机制一致；B 含 JFR 开销（66.8 s）仍快于无开销的 A（70.1 s）。

## 4. 被阻断的候选（诚实上限的依据）

历史诊断的 22% 重复 visitor CPU（本机实测：54.9% 类被完整处理两次，重复恢复 226.8 s ≈ 33.4% worker 占用）在本输出合同下**不可收割**，四个架构逐一 fail-closed：

1. **认证恢复摘要 v1**（只证 noCode 子树）：f=0.086，省 ~2 s，低于噪声，低于 f≥0.5 硬停线。
2. **stage-2a 放行注解类**：f≈0.13；coded 类的 pass 裁剪因 retained pass 需要完整 POST IR（PrepareForCodeGen/CodeShrink 读 basicBlocks、CheckRegions 二轮诊断可达）而无法证明，全部 KEEP。
3. **stage-2b 方法级稀疏前缀**：等价证明义务（T1/T4 闭包、POST-IR 读者链）未闭合，未实施。
4. **驻留式延迟卸载**（Option B）：实测等价性反例 —— 保留态与 legacy 重入重建态在注解字段 useIn（3 vs 2）、FixAccessModifiers 可见性、codegen 隐藏标志上均不同，整类裸保留会改变输出。
5. **monitor 等待**（~5.6% wall）：主要是重入的症状，随重入减少而部分消退，独立杠杆价值有限，未单独实施。

其余 JFR 热点（HashMap 查找 ~12%、`AttrNode.contains(AFlag)` 2.3%、BitSet/live-var ~3%）单项均 ≤3%，叠加预期 ~5% 以内，未在本轮展开。

## 5. 复现

```bash
git clone https://github.com/ltlly/jadx.git && cd jadx
git switch --track origin/codex/perf-macos
JAVA_HOME=<jdk21> ./gradlew :jadx-cli:installDist -I performance/20261010/mirrors.init.gradle
python3 performance/20261010/measure_macos.py --jadx-bin jadx-cli/build/install/jadx/bin/jadx \
  --java <jdk21>/bin/java '--jvm-args=-Xms16g -Xmx16g' --apk <apk> --out-dir <out> \
  --label A --round 1 --jsonl runs.jsonl --tsv runs.tsv --log-dir logs
# CLI: --no-res --threads-count 8；审计：audit_macos.py baseline/diff
```

门禁：`./gradlew :jadx-core:test :jadx-cli:test :jadx-core:spotlessJavaCheck :jadx-core:checkstyleMain :jadx-core:checkstyleTest :jadx-cli:spotlessJavaCheck :jadx-cli:checkstyleMain :jadx-cli:checkstyleTest` → BUILD SUCCESSFUL，1,116 tests / 0 failures（A 基线 1,039 → +53 个新回归测试）。

## 6. 未检查项与实质风险

- 输出等价性按“文件集 + 省略体 + 错误行 + token 变体归因”口径验证；与历史结论一致，这不构成语义等价证明，也不声称可重编译。
- 并发整体行为未被证明等价（jadx 既有跨类 useIn/inline 竞态在 A/B 均存在；本轮 B 未加重，钉钉上反而消除 2 处）。
- GUI 加载路径与单类 `JavaClass.decompile()` 路径对候选 1 的并行前端有串行回退保护（能力探测 fail-closed），但未做 GUI 实测（不在本目标范围）。
- 依赖摘要代码（默认关闭）保留在树上；后续若要重启该方向，应从 stage-2b 的证明义务清单开始，而非放宽门禁。
