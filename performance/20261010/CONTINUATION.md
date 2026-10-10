# 后续算法研究与候选撤回记录

**最终保留的源码仍为六项优化端点 `26552586d64e43b0a4806f6cc70c5a2202731dd1`，千问全量 wall 中位数下降 22.25%。** 第七项候选虽测得累计 25.62%，后续发现 Telegram 新增不合法的受检异常覆盖声明，已撤下；不得将 25.62% 写作交付收益。使用列表索引也仅保留在隔离实验分支，不合入最终源码。

本轮继续以修复了已确认语义问题的 `8e35efc4e156c20d2af40c0a160eb78cdcfe9f40` 为累计性能基线。争取千问全量 Java 反编译耗时下降至少 50%，不允许为此牺牲质量。十个固定 APK 均需加速或没有可重复的性能回退；此前交付的 22.25% 千问收益不代表达到目标。累计结果直接交错测量，不能把不同测量批次的收益相加。

沿用 [复现流程](REPRODUCE.md) 中的固定 JDK、16 GiB 最大 heap、8 个反编译线程和输入 SHA。正式 A/B、构建、回归测试、JFR 和诊断运行串行执行。采样和带断言的诊断运行不进入性能统计。

## 已撤回第七项候选的独立测量

表中是三轮交错 A/B 的中位数，范围在括号内。CPU 为 user + system 秒，RSS 为 KiB。

| 改动与样本 | A wall | B wall | wall 改善 | A CPU | B CPU | A RSS | B RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| UsageInfo.apply 局部复用方法解析，千问 | 67.59 (67.35–68.50) | 65.87 (65.37–66.46) | 2.54% | 405.24 | 405.46 | 17640432 | 17644284 |
| 同一改动，Telegram | 34.42 (33.96–34.78) | 34.10 (33.48–34.26) | 0.93% | 169.33 | 168.15 | 15591064 | 15615800 |

这一候选的源码提交是 `b321cdaf6bfc6434c15a608a8f36193470928694`；A 为此前六项优化的源码端点 `26552586d64e43b0a4806f6cc70c5a2202731dd1`，B 为 `fixed-usage-resolve` 冻结构建。两个样本的三对 wall 都更快，千问 CPU 没有改善，RSS 范围重叠。这里只有局部耗时收益，没有证明该项降低峰值内存。逐次数据及 CPU/RSS 的全部范围见 `withdrawn-resolution-incremental-measurements.tsv` 和同名 `.summary.tsv`；原候选分支已命名为 `codex/withdrawn-usage-resolution`。

## 已撤回七项候选的直接累计测量

2026-10-10 新运行三轮串行交错 A/B：A 为共同正确性基线 `8e35efc`，B 为七项优化候选端点 `b321cda`。这里不使用增量收益相加。

| 千问 7.3.6 | 基线 A 中位数（范围） | 优化 B 中位数（范围） | 中位数下降 |
| --- | ---: | ---: | ---: |
| wall 秒 | 88.12 (86.73–88.29) | 65.54 (64.93–66.17) | 25.62% |
| CPU 秒 | 509.21 (504.75–510.95) | 409.37 (397.42–410.09) | 19.61% |
| 峰值 RSS KiB | 17728640 (17703852–17766840) | 17647216 (17629520–17687160) | 0.46% |

三对耗时均更快；RSS 变化很小，不解释为显著内存收益。基线 core JAR SHA-256 为 `27ee799f7f49e7838ccaf52c8d7641b56ba86cbbc837577bf8a58dbdec4588c3`，优化版为 `23a324a7433aa3c6059059f647b971e4f8a40b0dabc4deb7fbd6076dcf9f0dc6`。每次命令、退出码、输入和分发包哈希、CPU/RSS 及文件内容清单保存在 `out/jadx-perf/20261010/continuation/cumulative-qwen`；汇总为同目录上级的 `cumulative-measurements.tsv` 与 `cumulative-measurements.summary.tsv`。此表只记录候选千问结果，因质量问题不进入保留收益；其他九个 APK 尚未重跑第七项加入后的累计对照。

六次均退出 3，均有 71,047 个 Java 文件，无缺失或额外文件。A 错误数 1,032，B 为 1,032–1,033；省略方法体 A 为 904–913，B 为 905–916。逐对有 71–96 个类的 Java token 不同，A 自身重复运行也有 75–77 个类不同。不能用这些总数豁免内容检查。

汇总审查 243 个候选整类 token 变体：237 个匹配已有参考输出（参考包含共同基线及历史优化端点，并非全部来自共同基线），2 个仅添加外层直接重抛，4 个进入人工审查。`customize.s.onItemClick` 新省略的整类输出与共同正确性基线普通全量 CLI 完全一致；`BuildersKt.launch$default` 新省略与原始版普通全量 CLI 完全一致；`SegmentDownloader.download` 的候选变体也匹配共同基线全量 CLI。`RealBufferedSource.skip` 的 throws 变化有 15 个历史整类匹配。余下一项 `customize.k0` 没有整类历史匹配，唯一 token 变化是在匿名 Runnable 内删除空的 `AnonymousClass5() {}`；已断言除此五个 token 外完全相同，空体没有语句。没有宣称完整 APK 能重新编译或设备行为等价，也没有将已有省略当作正常方法体。

证据为 `cumulative-quality.tsv`、`cumulative-quality.details.json`、`cumulative-qwen-extra-references.json`、`cumulative-qwen-manual-resolution.json` 及运行目录中的 token 审查文件。B 的单次额外错误事件尚未独立归因，保留为限制。

候选端点重新完成 core/CLI 共 1,064 项测试，零失败、零错误、1 项版本条件跳过；core/CLI 格式与 Checkstyle 通过。日志为 `logs/jadx-perf/20261010/continuation-final-tests.txt`，XML 快照及统计为 `out/jadx-perf/20261010/continuation/retained-final-tests`。[已撤回候选的独立补丁](withdrawn-usage-resolution.patch)的 SHA-256 为 `15fd915f535381cc251ad5787d945a4f86b2e2ea01fd3ab6410507734de0a23a`；在独立 Git index 应用至直接父提交所得 tree 与 `b321cda` 相同，记录为 `usage-resolution-patch-verification.json`。本目录另保存 `continuation-measurements*.tsv` 和 `continuation-quality*.tsv/json` 的精简快照，完整输出仍在 out 目录。

## 语义与输出检查

方法解析只在一次 `UsageInfo.apply()` 内复用；类重新加载及再次应用时重新解析。新增回归覆盖节点身份、深度卸载后新节点，以及缺失方法的原异常。五项相关测试、格式和 Checkstyle 已通过。一次独立千问诊断将 7,926,804 次解析逐次与原逻辑比较，835,153 个不同节点，返回身份零差异。

千问三轮均输出 71,047 个 Java 文件，没有缺失或新增文件，也没有候选版独有的省略方法清单项。241 个发生差异的候选整类 token 变体中，227 个与已有参考逐 token 一致，1 个只增加外层直接重抛，13 个尚需审查的变体仅有 `throws` 声明变化，方法体 token 未变。不能因此宣称全部源码逐 token 等价或能够重新编译。

Telegram 三轮均输出 16,163 个 Java 文件，A/B 文件清单相同。91 个候选变体中 89 个已与基线整类 token 匹配。`com.google.android.gms.internal.vision.a1` 的构造函数省略已由未修改的前一端点通过普通 API 类处理顺序复现，整类 token 与 B2 相同；这项控制不是全量 CLI 运行。`ga.l` 新出现的 `throws la.c` 声明尚未在普通类顺序控制中复现。继续检查发现 B3 中 `b()` 等覆盖方法新增此声明，但父类 `la.a.b()` 没有 throws，且 `la.c extends IOException`。这是受检异常覆盖合同的冲突，不能按“仅 throws 变化”豁免。第七项因此撤回；抽取实际父子方法声明的 Java 11 编译检查及证据见下节。

原始结果、文件哈希、错误、token 差异和省略方法清单位于工作目录下 `out/jadx-perf/20261010/continuation/usage-resolve` 与 `usage-resolve-representative`。所有本轮正式反编译进程仍返回已有错误对应的退出码 3，不能把基准脚本的成功退出当作反编译无错误。

## Telegram 声明冲突的复核

候选 B3 的实际输出与 A3 对照：`la.a.b()` 在两端均为 `public void b()`；A3 的子类为 `public final void b()`，B3 为 `public final void b() throws la.c`，两端 `la.c` 均继承 IOException。抽取这三个类的继承关系及 b 方法声明，保留真实访问标志和异常类型，以空方法体隔离声明合法性；这不是完整 APK 编译或方法体等价性测试。检查脚本为 [check_telegram_throws.py](check_telegram_throws.py)，编译结果与真实源文件哈希保存在 `telegram-throws-signature-check.json`。Java 11 对抽取后的 A3 声明返回 0，对 B3 声明返回 1，明确报 `overridden method does not throw c`。

已有共同基线曾出现 `ga.l.x()` 的异常声明差异，说明原版异常推断也受时序影响；这不能豁免候选新出现的其他方法冲突。尚未证明第七项是该既有问题的根因，但在当前质量门槛下仍撤回候选。没有为了保留性能数字压掉异常声明、警告或方法体。

声明检查可单独复现，最后的结果目录必须尚不存在：

```bash
python performance/20261010/check_telegram_throws.py \
  out/jadx-perf/20261010/continuation/usage-resolve-representative/telegram-A3 \
  out/jadx-perf/20261010/continuation/usage-resolve-representative/telegram-B3 \
  out/jadx-perf/20261010/toolchains/jdk-11.0.32.1+1/bin/javac \
  out/jadx-perf/reproduce-telegram-throws
```

## 其他撤回的尝试

`ClassInfo` 直接保存不可变原始类名（实验提交 `30061c5cb4c2d8d59bf2ef1d1da2f29fedf8deab`）通过了 25 项定向测试及格式检查，但千问三对 wall 均回退：65.54 (65.21–66.22) → 66.65 (66.15–66.86) 秒，CPU 中位数 402.87 → 407.64 秒。已从当前保留分支撤回；实验分支 `codex/raw-class-name`、冻结分发包和完整测量仍保留，不计入收益。

使用位置索引实验把重复线性搜索改为维护有序首次位置；DuckDuckGo 三轮全量 wall 中位数下降 50.29%，但千问没有稳定 wall 收益、CPU 增加 2.72%，DuckDuckGo RSS 增加 0.42%、三对均更高，且有未解释输出变体。实验也撤下；详细范围、复杂度、1,069 项测试、逐查询影子校验、内容检查及复现命令见 [算法评估](ALGORITHM_REVIEW.md#索引实验最终测量与撤回决定)。这个 50.29% 是 DuckDuckGo 对直接父候选的增量收益，不能写成千问收益、最终累计收益或已通过质量门槛。

## 下一步的证据

第七项候选千问 JFR 有 17,614 个执行样本，其中 318 个栈截断。TypeSearch.run 包含样本为 14.28%，CFG 遍历 6.80%，TypeUpdate.apply 6.28%。这些包含比例相互重叠，不能相加。前约 20 秒主要由主线程加载、预处理和应用使用关系，未发现需要优先解决的单工作线程长尾。

诊断中 17,334,301 个 TypeUpdateInfo 对象仅 8,922,049 个登记了更新。四行延迟分配实验与必要回归通过了 149 项类型相关测试，但没有正式性能测量；按用户后续要求优先算法收益，已搁置并恢复源码，不计入保留补丁。候选类型重复检查约占 6.7%，尚不支持增加通用候选结果缓存。已知普通类的赋值约束可排除约 743 万个窄类型候选，诊断零误排，但仅覆盖约 15.9% 的已知候选检查；即使完全消除这部分扫描，对全量 CPU 的机会也远小于整个类型填充阶段的约 9.2% 样本比例。泛型、数组、动态边界及未知类型的证明成本使其暂时降级。

后续每项优化先记录当前热点、原算法复杂度、候选算法、全量收益空间、实现与验证成本，再决定是否实施。CPU 样本比例不能直接当作墙钟比例，包含样本也不能相加。算法研究与筛选见 [算法评估](ALGORITHM_REVIEW.md)。

## 算法筛选与保留范围

已核查 DDC、Droid ASC、SAILR、Soot 和 Vineflower，以及 2025–2026 的相关研究；具体来源、算法复杂度、测得机会和安装取舍在 [算法评估](ALGORITHM_REVIEW.md)。没有安装不适用于全量 DEX 的模型或原生二进制分析栈，也没有将外部项目自报倍数套用到 jadx。

重复整类处理是本轮识别出的最大机会：51,606 个顶层类重复处理，重复 visitor CPU 91.441 秒，约占独立诊断进程 CPU 的 22.01%。这不等于可消除的 wall 比例。反转生成顺序的实验提交 `56039433d4561133884612d25dd4f8853125dec3` 已撤回：单次 JFR 初步耗时改善约 6.7%，却有 2,739 个类的 Java token 变化，以及 6 个相对该次对照新增的省略方法体。没有将其计入保留收益，未做无意义的三轮全量扩测。

类型候选求交的新诊断零误剪，但安全覆盖仅约 16.5% 的已知候选检查；动态边界预解析约占 1.58% CPU 样本，且涉及跨类可变元数据。两项均因预期机会与证明成本不合适而搁置。用户随后允许成本较大而收益较大的改造，前提仍是实测收益和质量不退化；依赖生命周期与串行前端进入诊断和隔离原型评估，不预先保证未测收益，没有直接跳过已生成依赖的分析。

最终保留的源码端点仍为六项优化 `26552586d64e43b0a4806f6cc70c5a2202731dd1`。`b321cdaf6bfc6434c15a608a8f36193470928694` 属于已撤回的第七项候选。历史十 APK 的直接累计对照只覆盖六项优化 `2655258`，见 [主报告](README.md)；不能把它说成第七项加入后的十样本结果。已撤回的新增项已有千问和 Telegram 的独立三轮测量，上表给出全部范围。算法研究、诊断和撤回实验均未混入正式计时。

## 复现候选数据（不是保留端点）

保留六项的复现直接使用 [REPRODUCE.md](REPRODUCE.md)。重现本轮已撤回候选时，沿用 [REPRODUCE.md](REPRODUCE.md) 的同一 checkout 构建步骤，将优化端点 `26552586d64e43b0a4806f6cc70c5a2202731dd1` 换为 `b321cdaf6bfc6434c15a608a8f36193470928694`；基线仍为 `8e35efc4e156c20d2af40c0a160eb78cdcfe9f40`。不能把系统安装的 jadx 或诊断分发包作为任一端。

当前工作目录保存的两个冻结包可以直接复测。使用尚不存在的结果目录，保持串行；下面命令先测千问，另行复现第七项的独立样本时将 baseline 换成 `fixed-use-after` 并指定 `--names telegram`。

```bash
cd /home/ltlly/Code/jadx-perf
python performance/20261010/benchmark.py \
  out/jadx-perf/20261010/artifacts/fixed-baseline \
  out/jadx-perf/20261010/artifacts/fixed-usage-resolve \
  out/jadx-perf/20261010/corpus \
  out/jadx-perf/reproduce-continuation/qwen --names qwen-7.3.6
```

本轮仍未达到争取的 50%，没有交付第七项或使用列表索引。未重新运行这些候选加入后的十个 APK 最终累计 A/B。历史输出中的并发和类顺序差异也尚未全部得到逐项证明；GUI、其他性能 JDK、资源输出、完整 APK 源码重新编译和设备行为等价没有检查。
