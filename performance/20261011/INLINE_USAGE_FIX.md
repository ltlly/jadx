# InlineMethods 使用关系并发修复

本次针对旧提交 `20f2d41e2c931243e1b4c0609f548003ed6e7606` 的共享使用关系更新，修改 8 个生产文件并增加回归。原始记录集中在本机 [`out/jadx-perf/20261011/inline-usage-fix`](../../out/jadx-perf/20261011/inline-usage-fix)。此次修复不跳过内联、验证或其他反编译阶段；APK 整体语义等价与性能收益需另行判断。

## 定位与修复

历史 Qwen B3 的 [`statuscontainer.d.invoke(Object)`](../../out/jadx-perf/20261011/dependency-profile/qwen-B3/output/sources/com/aliyun/tongyi/uikit/widget/statuscontainer/d.java) 在内联 `StatusContainer.e(StandardOverlay):Unit` 时出现 `ArrayIndexOutOfBoundsException`（index 83 / length 82）：

```text
ArrayList.add:485/497
ListUtils.safeReplace:157
InlineMethods.replaceClsUsage:193
InlineMethods.updateUsageInfo
```

旧路径原地修改目标类的普通列表；当前调用者的类处理锁不能排斥其他类更新同一目标。方法、字段列表另有“读快照、复制、再设置”覆盖另一线程更新的问题；`MethodNode.setUseIn` 还回写调用者的 `methodsUsed` 集合。历史栈与该共享写入机制吻合，实际两个竞争写入者尚未识别。

修复将所有已知增量更新移到节点方法：内联的删除与替换、`ProcessAnonymous` 的增删、字段追加，以及 `UsageInfoVisitor.replaceMethodUsage` 的合并。现有 `safeReplace` 的首项替换/追加、重复项处理和合并排序规则保留。合并先复制结果，覆盖 `distinctMergeSortedLists` 在一侧为空时直接返回输入列表的情况。

## 所有权、锁顺序与 API

- 每个 `RootNode` 持有一个使用关系监视器。增量更新在锁内读取当前列表、复制、修改并发布；锁内同时维护方法的反向引用，`getUsed()` 在同一锁内清理失效引用后返回独立集合。
- 类、方法、字段的使用列表以 `volatile` 发布只读快照。完整 setter 在锁外复制外部输入，再在锁内发布；初始化和深重载仍完整替换使用关系，不合并旧结果。
- 持有使用关系锁时不获取类处理锁、不触发解析或反编译。现有调用路径中的节点属于同一个 root；反向通知只重入同一监视器。普通生成后卸载保留使用关系，锁的生命周期随 root 结束。
- `ListUtils.immutableCopy` 使用标准 `unmodifiableList(new ArrayList<>(...))`，保留完整 List 读取接口、null 元素及常规 `toString()`。现有 `Utils.lockList` 的 `ImmutableList` 不提供相同的读取接口和字符串格式。
- 仓库内 getter 调用方只读；`JavaClass`、`JavaMethod`、`JavaField` 的公开 API 仍转换为独立列表。直接修改内部 node getter 返回列表的外部插件需改用节点更新方法。

快照保证单次取得的列表稳定；跨多个 getter 的读取和整个 `updateUsageInfo` 并非一个原子事务。任意并发深重载涉及节点身份替换，不属于此次保证范围。

## 回归与证据版本

[`InlineMethodsUsageTest`](../../jadx-core/src/test/java/jadx/core/dex/visitors/InlineMethodsUsageTest.java) 使用四个 [smali fixture](../../jadx-core/src/test/smali/inline/TestConcurrentMethodInline)。两个线程各持有自己的调用者类处理锁，通过真实 `InlineMethods.visit` 执行无寄存器的内联 IR；故障 gate 控制旧列表的复制或查找交错。断言分别检查桥接方法、成员、类、反向引用，并检查替换后的 IR。

| 当前测试 | 覆盖 |
| --- | --- |
| `concurrentInliningRemovesEveryBridgeCaller` | 桥接方法的调用者全部移除 |
| `concurrentInliningPreservesTargetMethodCallers` | 目标方法保留两个调用者 |
| `concurrentInliningPreservesTargetFieldCallers` | 目标字段保留两个调用者 |
| `concurrentInliningPreservesClassCallers` | 类与类的方法使用列表保留两个调用者 |
| `usageSnapshotsDetachInputsAndRemainStable` | setter 脱离输入、旧读取视图稳定、反向集合独立 |
| `replacingMethodUsageHandlesEmptyAndOverlappingSnapshots` | 两侧分别为空、重叠合并、排序及反向引用清理 |

旧实现上的 `old-regression-v2` 只含前五项，实跑 **5 tests / 5 failures / 0 errors / 0 skipped，退出 1**。失败包括桥接引用残留、目标引用丢失、反向关系不符和输入别名泄漏；不是六项测试全部在旧实现失败。对应 [源码](../../out/jadx-perf/20261011/inline-usage-fix/old-regression-v2-source.java)、[命令](../../out/jadx-perf/20261011/inline-usage-fix/old-regression-v2-command.json)、[XML](../../out/jadx-perf/20261011/inline-usage-fix/old-regression-v2.xml)、[日志](../../out/jadx-perf/20261011/inline-usage-fix/old-regression-v2.txt) 和 [退出码](../../out/jadx-perf/20261011/inline-usage-fix/old-regression-v2-exit.txt) 均保留；该测试源码 SHA256 为 `f069bd5ef1e35325f5b5c6b3c22c4ec0ac132958d8e3fd4d684f1c1949f96b81`。

较早修正版的 [定向 XML](../../out/jadx-perf/20261011/inline-usage-fix/fixed-targeted-regression.xml) 记录这五项全部通过；该次 [Gradle 日志](../../out/jadx-perf/20261011/inline-usage-fix/fixed-targeted.txt) 随后因测试辅助类继承 `ArrayList` 触发 Checkstyle `IllegalType`，整体退出 1。当前源码已增加局部说明与抑制，并补充第六项；前述五项记录不能代替最新六项及完整检查的结果。

修正版 setter 会在 gate 启用前脱离原列表。这组测试提供旧路径的受控失败和当前并发结果检查，不穷尽所有线程交错，也未重现历史 APK 的精确 `ArrayList.add` 越界。

## 完整检查与固定版本

修复独立提交为 `0bf6c9d67dbb65ead37476337a95996a7431dc3a`。Java 11 完整 core/CLI 检查报告 **1133 项，其中 1132 通过、1 既有条件跳过、0 failures/errors**，其中最新六项回归全部通过；格式、主/测试 Checkstyle 和 `installDist` 通过，Gradle 退出 0。命令、日志、逐 suite 计数与 XML 归档分别保存在原始证据目录的 `full-check-command.json`、`full-check.txt`、`full-check-counts.json`、`full-check-results.zip`。

A 使用先前冻结的作用域修复分发包，对应 `20f2d41`；B 是本次 `fixed-distribution`。全部 62 个文件已绑定 SHA256，只有 core JAR 不同；源码、production patch 与测试源哈希保存在 `baseline-artifact.json`、`fixed-artifact.json`。六轮固定工作量已按 A1/B1/A2/B2/A3/B3 串行完成。固定千问 7.3.6、JDK 27、16 GiB heap、8 线程、全部 Java 输出、关闭资源，隐式 JVM 参数已清理；命令、主机活动、退出码、输出清单和计时分别保存在各轮目录。

| 指标 | A 中位数（范围） | B 中位数（范围） |
| --- | ---: | ---: |
| wall 秒 | 123.54（122.86–125.44） | 126.99（123.39–128.35） |
| 进程 CPU 秒 | 754.65（752.22–781.68） | 744.83（732.45–745.53） |
| peak RSS KiB | 16313224（16109864–16325648） | 16026020（16012824–16117664） |

此次 B wall 中位数约增加 2.8%，CPU 降约 1.3%，RSS 降约 1.8%。wall 和 RSS 区间重叠，CPU 区间不重叠。存在主机背景活动；这些是整体开销观测，不能将全部时间差归给使用关系锁，也不能据此把正确性修复算作 wall 提速。

六轮均退出 3、输出 71047 Java 文件；A 三轮及 B1/B3 错误总数 1032，B2 为 1033。三轮 B 没有原 InlineMethods 越界。逐对没有文件缺失或新增；Java token 变化分别 117/83/95 类。B 占位方法数 914/918/912；相对三轮 A 并集多 7 项，其中 6 项已在前八份历史审计产物中出现，剩余 `i0.onItemClick` 的普通历史基线复现见后文。这些记录只解释具体失败变体，不证明全部 token 差异语义等价。原始汇总为 `measurements-and-quality.json`，统一 diff 和逐方法清单也保留。

机器可读摘要见 [inline-usage-fix-results.json](inline-usage-fix-results.json)；后续性能实验见 [TYPE_BOUND_SELECTION.md](TYPE_BOUND_SELECTION.md)。

## 结果边界

APK 对比需保留相同完整输入、JDK、heap、线程数及版本，记录错误、文件清单和完整类 Java token 差异。过滤类会改变竞争参与者；顺序调用 `getCode()` 即使配置 8 线程也不能证明跨类竞争消失。某一轮未再出现占位方法不能单独证明整体语义等价。

未完成的验证包括历史实际写入者识别、GUI/外部插件与并发深重载覆盖；三轮测量给出了复制与同步后整体开销的观测，尚未单独精确计量 root 锁竞争。此次不从定向回归或历史输出匹配推导整体语义等价与性能收益。

## B2 的匿名构造器代码生成错误

本轮 B2 的 [`i0.onItemClick`](../../out/jadx-perf/20261011/inline-usage-fix/qwen-B2/output/sources/com/ucpro/feature/setting/developer/customize/i0.java) 在 `InsnGen.inlineAnonymousConstructor:829 → MethodNode.getArgRegs` 失败：`CleanTestConfigFactory.3.<init>():void` 的参数寄存器未加载，报告的顶层类状态为 `NOT_LOADED`。B2 文件 SHA256 为 `6b64abc9525afe3dc1c9598cdb430ce86efb9e17e2ab5eab6f07c1ea8ed9d87d`，与该轮 manifest 一致。

限定检查 177 份历史同路径及已有受控文件后，找到以下完整类 Java token 匹配；没有扫描其他数百万源码文件：

| 已有证据 | 运行性质与状态 | 文件 SHA256 |
| --- | --- | --- |
| [`qwen-lazy-candidates-A2/i0.java`](../../out/jadx-perf/20261010/qwen-lazy-candidates-A2/output/sources/com/ucpro/feature/setting/developer/customize/i0.java) | 旧版普通完整 CLI，无采样；同构造器、同错误、`NOT_LOADED` | `40a5020ac7bdc2160da6730569c72e20bc963aeb3c76890317985e0ddf4308c5` |
| [`qwen-cfg-iteration-profile/i0.java`](../../out/jadx-perf/20261010/qwen-cfg-iteration-profile/output/sources/com/ucpro/feature/setting/developer/customize/i0.java) | 旧版完整 CLI，启用 JFR；同构造器、同错误、`NOT_LOADED` | `40a5020ac7bdc2160da6730569c72e20bc963aeb3c76890317985e0ddf4308c5` |
| [`repro-fixed-unload-i0/interleaved.java`](../../out/jadx-perf/20261010/verified/repro-fixed-unload-i0/interleaved.java) | 共同正确性基线的受控卸载顺序；同构造器错误，状态为 `GENERATED_AND_UNLOADED` | `20227b19e2f9457d2bed34764c914e326a686b51722a20dfca63a2a0add43b6e` |

上述三份与 B2 整类 token 完全一致。比较使用既有 `audit.tokens()`，再以 `json.dumps(tokens, ensure_ascii=False, separators=(',', ':'))` 编码为 UTF-8 计算 SHA256；共同 token 哈希为 `63068dcdc51bea17e13cdf7c0e8b1ebe7f82686d4cd4513a87419f3602ca7940`。这保留整个占位方法及其他 Java token，没有删除失败方法后比较。

**普通旧 A2 的版本绑定：**[metadata](../../out/jadx-perf/20261010/qwen-lazy-candidates-A2/metadata.json) 记录开始时间 `2026-10-10T01:30:51+0800`、完整 Qwen 7.3.6、JDK 27、`-Xms256m -Xmx16g`、`ParallelGCThreads=3`、`--no-res --threads-count 8`；APK SHA256 为 `b793d73e8365b9ef591c9b343d83c62123f96a05b1e167ec792ba91c0350b7f1`。该次退出 3，输出 71047 个 Java 文件。metadata 中 core JAR 哈希 `b9a7d4b067ec29d4f8b6b960a7e5d1c176fc78fdf84a9e8ae5b6fb58509bea37` 与现存 `artifacts/root-names/lib/jadx-core-dev.jar` 实算一致，[builds.legacy.tsv](../20261010/builds.legacy.tsv) 将其绑定到 `6fc287156c9d5d38399e7b5dec0169f2c33b2d3f`。metadata 自身 SHA256 为 `5421c4be8a75a52ffe1a938e8100cf86309f8352d54b7c26f7dbf630f3ac15db`，文件 SHA 与 manifest 也已核对。它是早期性能阶段的 A，尚未采用后来的共同正确性修复，**不是本轮 `20f2d41` 的 A**。

第二份 JFR 记录的 core JAR 为 `9df7d31bb6f307d110f1134dbb7796ca01e7adfef3f4a063dd10ff4e12691a73`，现存文件及旧 builds 表一致，对应 `a972acead41d2dafe87a0860de29df111f89546c`；同样输出 71047 个 Java 文件、退出 3，source SHA 与 manifest 一致。

**受控证据的绑定限度：**旧 [quality-review](../20261010/quality-review.json) 将 `repro-fixed-unload-i0` 归于共同基线；[builds.tsv](../20261010/builds.tsv) 绑定该基线 `8e35efc4e156c20d2af40c0a160eb78cdcfe9f40` 与 core JAR `27ee799f7f49e7838ccaf52c8d7641b56ba86cbbc837577bf8a58dbdec4588c3`，现存 JAR 哈希相符。[日志](../../logs/jadx-perf/20261010/measure-live-reuse.txt) 的 `UNLOAD-CONTROL i0` 段保留成功控制及失败交错；日志 SHA256 为 `ac8c861f3135d9a229fa77589c6a37958bc73699e57c276b654f7441c2138b8a`。现存 [helper 源码](../20261010/ReproduceDependencyUnload.java) SHA256 为 `c7a2462e9e215299d9ce494d03e08f9bc010b65157b81f63f303f2abdadb8783`；它在处理消费者依赖后，先生成并卸载 `CleanTestConfigFactory`，再生成消费者。该受控目录没有独立运行 metadata/分发清单，当前 helper 也不能补充证明当时编译输入的哈希。其证据强度低于前述普通旧 A2 的 manifest 绑定，且不同状态不能混写。

代码中存在对应的生命周期缺口：`ProcessClass.generateCode` 处理依赖后释放依赖类锁，再生成消费者；消费者锁不覆盖该匿名类的顶层依赖。`InsnGen.inlineAnonymousConstructor` 的 `ensureProcessed()` 只读取状态，`ProcessState.isProcessComplete()` 还接受 `GENERATED_AND_UNLOADED`，不保证构造器 IR 常驻，也不持锁保护后续 `getArgRegs()`。`ClassNode.unload()` 会清空方法参数寄存器并置 `NOT_LOADED`；普通生成随后再置 `GENERATED_AND_UNLOADED`，重新处理已卸载类也先置 `NOT_LOADED`。这些路径解释了既有失败机制，但 B2 没有线程轨迹，未确认实际卸载者和交错位置。

本次 usage 修改没有改变上述代码。`ProcessAnonymous` 的直接改动仅将原列表增删移至节点原子更新方法，匿名检测条件、依赖移动和 codegen 依赖合并逻辑保留。快照及串行更新仍可能改变并发调度和重载时读到的引用内容；它们不把多次 getter 读取变为事务，不能仅凭代码未直接改动卸载路径就排除输出影响。

结论仅为：同一坏输出及精确 `NOT_LOADED` 错误在本次 usage 修复前的普通完整 CLI 中已经出现，另有共同基线受控证据。它不证明本轮 A/B 全局等价，也不证明修复对该失败概率没有影响，不能据历史 token 匹配直接质量放行。本节只读取已有输出并核对哈希，未运行新构建、测试或反编译。

## 类型采样轮的字段查找错误

`qwen-type-profile` 使用与本次 B 相同的 62 文件分发包，另开启 JFR 和依赖恢复探针；它不计入六轮性能中位数，也不是类型 selector 候选。该轮错误总数为 1033；相对普通 B3 有 95 类 token 变化、2 类错误标记变化（Base64 溢出类型，以及下述新增错误）。新增的 [`BrowserActivity.onStart():void`](../../out/jadx-perf/20261011/inline-usage-fix/qwen-type-profile/output/sources/com/ucpro/BrowserActivity.java) 在 `0x0028 SGET` 解码时失败并生成方法占位。原栈为 `ConcurrentModificationException → ArrayList$Itr.checkForComodification/next → ClassNode.searchField:574 → RootNode.resolveField:663 → InsnDecoder.tryResolveFieldType:546 → decode/process → MethodNode.load → ClassNode.load → ProcessClass`。

本次已找到并检查的 185 份旧同路径 `BrowserActivity.java` 中，均未见 JADX ERROR，也没有与本轮完整类 Java token 相同的版本。因此，这个具体方法占位是本轮新观察；不能将它说成历史已复现。更早的 [`bottom-order A1 / DXWidgetNode.renderView`](../../out/jadx-perf/20261010/verified/incremental/bottom-order/qwen-7.3.6-A1/output/sources/com/taobao/android/dinamicx/widget/DXWidgetNode.java)（IGET `0x00B4`）及 [B1 / `defaultLayout`](../../out/jadx-perf/20261010/verified/incremental/bottom-order/qwen-7.3.6-B1/output/sources/com/taobao/android/dinamicx/widget/DXWidgetNode.java)（IGET `0x0087`）已记录同一 `ArrayList$Itr → ClassNode.searchField → RootNode.resolveField → InsnDecoder.tryResolveFieldType` 异常机制，但方法和偏移不同。

`ClassNode.searchField` 直接遍历可变 `fields`；`ExtractFieldInit.fixFieldsOrder` 对同一列表执行 `sort/removeAll/addAll`，是现存并发写入候选，实际写线程尚未识别。usage 修复未直接改动上述字段列表和解码路径，但新增锁、分配及 JFR 可能改变线程交错。现有证据支持旧字段列表竞态机制在本轮触发，不能排除触发概率变化，也不能据此质量放行或宣称整体语义等价。本节只审阅已有输出及源码，未运行新构建、测试或反编译。
