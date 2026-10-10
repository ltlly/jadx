# 千问依赖恢复：本轮诊断结论

入口：分支 `codex/dependency-recovery-research`。工具契约见 [DEPENDENCY_PROCESSING_PROFILE.md](DEPENDENCY_PROCESSING_PROFILE.md)，汇总与构建绑定见 [dependency-profile-results.json](dependency-profile-results.json)，完整 pass 表见 [qwen-dependency-stages.tsv](qwen-dependency-stages.tsv)。这是研究进展，不是新的提速结果；50% 和 1 秒目标均未达到。

## 已完成与中断

- Java 11 全量 core/CLI 检查：1126 项、0 failures/errors、1 既有条件跳过；格式和主/测试 Checkstyle 通过。
- 未插桩 A=`7203687`，插桩源码 B=`6d0836b`，分发包分别冻结并绑定全部文件 SHA256；仅 core JAR 不同。B 的三次对照没有启用采集。
- 同 checkout 串行交错 A1/B1/A2/B2/A3/B3，固定千问 7.3.6、JDK 27、16 GiB heap、8 线程、全部 Java 输出、关闭资源输出。所有完成项退出 3，各输出 71047 Java 文件；退出 3 不表示反编译成功。
- 整批命令被平台 15 分钟限制取消。六个对照的完整指标、清单、计时和构建哈希随后独立核查；首个 `qwen-diagnostic` 未完成，排除。确认没有残留对应 JVM 后，以单独 420 秒 timeout 完成 `qwen-diagnostic-2`。
- 汇总 worker 因重复工具调用被终止，未采用其结论；对已知 TSV 做直接有界统计。

## 对照不是提速证明

| 中位数 | A 未插桩 | B 采集关闭 |
| --- | ---: | ---: |
| wall 秒 | 134.03 | 138.50 |
| 进程 CPU 秒 | 813.44 | 841.60 |
| peak RSS KiB | 16171148 | 16213932 |

A wall 范围 133.86–143.76 秒，B 为 127.30–146.78 秒。背景 CPU 活动和 kswapd 已记录；不能把这里的变化算成优化收益，也不能与历史 69 秒或另一设备的报告拼接。没有停止用户其他进程或 checkout。

## 成本：只解释此次诊断

诊断 wall 139.23 秒，进程 CPU 864.30 秒，RSS 16637604 KiB，退出 3。统计自身 CPU 505.339 秒；active/invalid/dropped 均为零，所有 stage 的 CPU known calls 等于 calls，248123 个轨迹事件的父 ID 均存在。

| 不重叠 stage 自身 CPU | 秒 |
| --- | ---: |
| 首轮 class visitor | 249.046 |
| 重入 class visitor | 183.826 |
| 首轮 class load | 12.254 |
| 重入 class load | 8.479 |
| 首轮 codegen | 31.746 |
| class process 自身其余：首轮 / 重入 | 11.320 / 6.651 |

重复 visitor 为本次进程 CPU 的 **21.27%**，为已计时 stage CPU 的约 36.38%。它是粗略待审成本池，不是可全部消除的成本，更不是 wall 上限：还没扣必需发布、IR 恢复和新机制开销。已计时 stage 与进程 CPU 的差额不能全部指认为 GC，里面还有未插桩前处理、编译和其他线程。

元数据请求中观察到 51550 次真正重入；另外有 23 次 LEGACY 重入，不得混入 metadata 放行。InsnDecoder 尝试首轮 815539 次、重入 626907 次，另有 codegen 458 次和单方法 reload 5 次；no-code 不计入。

最贵重复 pass：FixTypesVisitor **39.230**、TypeInferenceVisitor **20.354**、RegionMakerVisitor **15.757**、MethodInvokeVisitor **9.838**、SSATransform **9.687**、BlockProcessor **8.171 CPU 秒**。全部 pass 仍按原顺序执行。

## 访问与并行边界

四个 getter 记录 149901 次跨访问时顶层类的采样、67545 个目标方法；其中 codegen 125080、首轮 MethodThrowsVisitor 21619、重入 ProcessVariables 2522。getRegion/getArgRegs/getBasicBlocks/getInstructions 分别为 76012/51754/22119/16。127757 次采样目标处于 PROCESS_COMPLETE 且非空；这些不能解释成同等数量的必要完整恢复。

轨迹首个生成请求在 Root collector 起点后 31.269 秒，最后结束在 134.781 秒。八个生成线程的去重占用各约 99.6–100.9 秒；process 锁获取区间累计 96.857 秒，约 89.894 秒属于拿锁后已非重入的 metadata 请求。这个值包含观察器和调度时间，不能直接宣称减少同等 wall。

这些轨迹暴露等待及处理前阶段，但不构成整个 JVM 的关键路径；读者、别名和完整发布态前置闭包仍未闭合。零 getter 事件不能认证 body-free。

## 质量：没有通过等价门槛

- 八次审计（3 对 A/B、4 次同版重复、B1 对开启探针）均没有文件缺失/新增。
- A/B Java token 变化文件分别 84/87/92；A 同版重复 51/75，B 同版重复 112/90，开启探针对 B1 为 89。存在原路径漂移，不能把所有候选差异自动判为漂移。
- A1/A2/A3/B1/B2 错误总数 1032，B3 为 1033；B3 在 `com/aliyun/tongyi/uikit/widget/statuscontainer/d.java` 出现额外 InlineMethods 错误。尚未审完原因。
- Base64 的 RegionMaker 错误在 JadxOverflowException/StackOverflowError 之间变化，A 同版重复也出现变化；插桩代码和调用栈深度可能影响此类错误，不能承诺观察器全局无扰动。
- A 三轮占位方法并集 920，B 为 925，B 有 **6 个**未在任一 A 观测到的占位方法；开启探针对 A 并集另有 1 个、对 B 并集没有新增。未人工解释这些方法前，不接受此版本为语义等价或生产优化。

所有统一 diff、逐方法占位清单、stdout/stderr、host 活动和原始计时保存在本机 `out/jadx-perf/20261011/dependency-profile`；汇总含原始六张表 SHA256。源文件一致、错误数相近、同版漂移都不足以证明语义等价。

## 下一切口与停止条件

1. 先审 6 个新增占位方法和 InlineMethods 额外错误，排除插桩栈/调度影响；不放宽质量要求。
2. 从重复 FixTypesVisitor 的 39.230 CPU 秒追其输入、共享写入和所有读者，确认是否必须重新构造 SSA/区域。优先研究原 pass 内可证明的局部重复工作，而不是直接跳过 pass。
3. 发布态恢复仍要闭合内联、权限、throws、字段初始化、注解 useIn 重复发布及第二轮诊断的前置输入；未知插件/异常/深重载回退原流程。
4. 必需前置闭包若覆盖大部分完整流水线，就停止该恢复切口，转向其他独立瓶颈。约 21% CPU 成本池本身不足以承诺 50% wall，更不能承诺 1 秒。

未完成：生产恢复快路径、这些源码差异的人工语义审计、静默主机的新正式性能基线、整个 JVM 关键路径、GUI/插件/深重载覆盖及重编译验证。本轮没有新性能优化可合入。
