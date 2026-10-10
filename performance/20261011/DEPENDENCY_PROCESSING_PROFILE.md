# 依赖重入诊断（研究工具，不是性能优化）

目标：在原完整路径上量出可消除成本，再判断发布态/方法体分离是否值得实现。千问长期目标是全量反编译到 1 秒；当前没有实现或证明新的提速，也没有放宽摘要认证。

## 对照与开关

未插桩端点为 `720368750275925ab6740135e6de879c1c4b4dc0`，生产源码与 macOS 端点 `6e44d0218d75dae7ed2bdc8727ec4640231f1367` 相同。已冻结分发包到本机 `out/jadx-perf/20261011/dependency-profile/baseline`；旁边的 `baseline-artifact.json` 记录提交和全部文件哈希。后续构建必须另存，不覆盖它。

插桩默认关闭，只在 Root 创建时读取 `-Djadx.deps.profile=true`，不增加 visitor，不跳过分析，也不改变摘要开关。统计随该 Root 存活，没有全局结果缓存。输出路径 `-Djadx.deps.profile.output=/absolute/fresh/directory` 同样在创建时捕获，关闭 decompiler、重置 Root 之前写出。文件使用 CREATE_NEW，失败只记录诊断日志，不覆盖已有文件。

复用现有测量脚本，新增 `--dependency-profile`：

```bash
python3 performance/20261010/measure.py \
  out/jadx-perf/20261011/dependency-profile/instrumented \
  out/jadx-perf/20261010/corpus/qwen-7.3.6.apk \
  out/jadx-perf/20261011/dependency-profile/qwen-diagnostic \
  --dependency-profile
```

它沿用固定 JDK 27、16 GiB heap、8 线程、全部 Java 输出和资源关闭配置，移除隐式 JVM 参数，保留输入/分发包哈希、退出码、错误、清单和逐文件源码哈希。诊断输出在 Java 输出目录之外；时间包含采集及最后写出成本。构建、测试、JFR、诊断与正式 A/B 都串行，不能把诊断时间/RSS当作候选优化的性能结果。

## 六张表的含义

- `stages.tsv`：生成请求、实际进入处理路径的请求、class load/unload、每个 visitor、codegen，以及单方法恢复。包含 CPU/wall 和扣除同线程嵌套 scope 的自身 CPU/wall；包含时间不能相加。未进入锁的 PROCESS_COMPLETE 快返回不记为处理 scope。
- `classes.tsv`：顶层类名和 recovery 分组，累加其所有非 GENERATE_REQUEST scope。只有自身时间可用于类成本求和；calls 是 scope 数，不是处理次数，包含时间会重复计算。
- `timeline.tsv`：生成请求、处理和单方法恢复的线程、父事件、起止、锁获取区间。父事件跳过没有单独导出的 pass。上限为一百万事件；超出明确计入 dropped，不可据截断轨迹推导完整关键路径。
- `body-reads.tsv`：四个 getter（basic blocks、region、argument registers、instruction array）的跨顶层类采样；保留调用阶段、目标声明类/方法、访问时顶层类状态、非空采样和次数。返回集合/对象仍为原引用；参数未加载仍抛原异常。
- `decode-attempts.tsv`：在实际 InsnDecoder 分支前计数，不计 no-code 分支，不等于成功解码数。可区分当前 stage、recovery 和顶层状态；没有处理 scope 的调用标为 OUTSIDE_PROCESS。
- `status.tsv`：未闭合 scope、非法计时/嵌套、丢失事件，以及创建 collector 时的 JVM uptime（毫秒，仅用于粗略定位处理前阶段）。前面三项必须为零；使用 CPU 数据还需所有行 cpu_known_calls=calls。CPU 不支持时不伪装为零成本。

recovery 对 class process 指**真正拿到现有类监视器后**观察到 GENERATED_AND_UNLOADED，不按锁前状态判定；单方法 reload 单独标为 recovery。锁获取时间从 scope 起始到入锁后采样，包含少量观察器开销和调度时间，不是严格的纯监视器阻塞时间。

## 边界

这不是完整共享状态读写图。四个 getter 没有覆盖 SSA vars、this register、其他辅助查询、可变集合/region/instruction 别名、pinned 内联属性、跨 Root 对象或插件读者。顶层父类也可能随匿名类合并改变。**零事件不证明没有 body 依赖**，getter 非空不证明整个依赖类应恢复。聚合表不设行数预算；启用时有额外 CPU、分配与 RSS，可能扰动并发和固定超时，必须与关闭探针的输出/诊断交叉检查。

生成请求轨迹不是整个 JVM 的关键路径：输入、全局预处理、写文件、GC、编译和其他线程未全面插桩。worker 累计占用、线程 CPU、进程 CPU和 wall 必须分别报告；不能把前两者的占比直接承诺为 wall 提速。

未知插件、异常、深重载和未闭合效果的前置依赖均不能放行新恢复状态。现有 METHOD_WITH_CODE 拒收保持不变。后续按可消除 CPU、必要重放/IR 成本和 wall 关键路径交集选择最小可证伪原型；若必要前置闭包覆盖大部分完整流水线则停止此切口。

## 当前验证进度

- 依赖包 30 项定向检查与 core 格式/主测试 Checkstyle 通过：原 23 项，加 7 项计时、嵌套、并发、异常、错误输出和受控语义对照。
- 第一轮完整 core：1102 项，1 失败、1 跳过。失败为原有 `DependencySummaryConcurrencyTest.eightWorkersPublishOnce`（期望一次恢复，观察为零）；CLI 检查/构建尚未到达。正在未插桩端点复测，不能声明全量通过。
- 尚未完成 APK 诊断、源码审计或正式三轮 A/B。
