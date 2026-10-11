# 类型边界选择：局部优化实验

本轮先完成 [使用关系并发修复](INLINE_USAGE_FIX.md)，再在固定的 `0bf6c9d67dbb65ead37476337a95996a7431dc3a` 基线上单独研究类型推断分配。没有跳过 pass、验证、动态类型求值，也没有跨轮缓存。

## 热点证据

本机 `inline-usage-fix/qwen-type-profile` 为完整千问诊断运行，同时启用默认关闭的依赖恢复探针和 JFR。wall 140.32 秒、进程 CPU 822.08 秒、peak RSS 16085832 KiB；退出 3，1033 个错误、71047 个 Java 文件。它是诊断，不参与正式性能中位数。

`summarize_type_profile.py` 将采样栈中的 FixTypes resolver 和 TypeSearch 阶段分别分入不重叠桶，再按 JVM 启动时间、collector uptime 与线程轨迹近似关联首轮/重入。时钟锚点为毫秒精度，边界前后 1ms 的样本单列未知；偏移敏感性仍有变化，不能把比例乘以旧运行的 39.230 CPU 秒。

- 6735 个含 `FixTypesVisitor.visit` 的执行样本中，6139 个处在 `runMultiVariableSearch`。
- TypeSearch 的 fill/search 分别为 5109/949 个样本；其中已关联 metadata 重入的 fill/search 为 2184/376。423 个全局截断栈单列；FixTypes 栈没有截断。
- `TypeInferenceVisitor.selectBestTypeFromBounds` 作为首个 jadx 分配调用者的采样字节权重为 **4163182424**；FixTypes 对应 selector 权重为 **87355208**（同轮 `profile.summary.json`）。这些是采样权重，不是精确分配量或可回收的 CPU 秒。
- 动态边界会读当前 SSA、方法/类泛型及可变类型；本轮拒绝缓存求值结果或缩短搜索。

## 候选与语义约束

仅将两个 `selectBestTypeFromBounds` 的 `stream → map → filter → max` 改为按原集合顺序的循环。两种写法都是 O(n) 时间、O(1) 辅助状态；候选删除 stream pipeline 和归约对象，仍使用相同 comparator、每项读取一次当前类型、忽略 null type、返回 Optional。测试入口的两个方法由 private 改为包可见，没有新增公开 API。

比较器的 CONFLICT 双向都可能为负，不能排序或改变左右操作数。原归约以 `compare(best, candidate) >= 0` 保留 best；候选只在 `< 0` 时替换。相等时保留第一项对象，getter 与比较交错及异常传播顺序保留。

18 个参数化回归分别运行旧 stream 和两个生产 selector，覆盖空/null、冲突顺序、窄类型、相等对象身份、getter 改变泛型后的顺序、异常停止、真实 CHECK_CAST 的 SSA/index 变化。旧生产 stream（仅开放包可见测试入口）18 项通过。循环候选完整 Java 11 core/CLI **1151 项、0 failures/errors、1 既有条件跳过**，格式、主/测试 Checkstyle、installDist 均通过。

## 固定工作量与原始记录

原始记录目录为本机 `out/jadx-perf/20261011/type-bound-selection`。A 是已冻结的 usage 修复分发包；B 是本轮 candidate-distribution。全部 62 个文件绑定 SHA256，仅 core JAR 不同；源码与完整回归 patch、构建命令、XML zip 和退出码均保存。正式运行不启用探针或 JFR，沿用 `measure.py` 的固定千问、JDK 27、16 GiB heap、8 线程、完整 Java 输出及关闭资源设置。

## 正式筛选结果：撤回候选

| 指标 | A 中位数（范围） | B 中位数（范围） |
| --- | ---: | ---: |
| wall 秒 | 121.22（120.25–127.97） | 126.19（124.65–126.21） |
| 进程 CPU 秒 | 736.12（721.73–757.59） | 736.07（723.39–751.69） |
| peak RSS KiB | 16151500（16087260–16158440） | 16232460（16125232–16258616） |

六轮按 A1/B1/A2/B2/A3/B3 串行完成。B wall 中位数比 A 高 **4.10%**，CPU 基本持平（−0.007%），RSS 高 **0.50%**。三个范围均有重叠，主机活动记录保留，不能把全部时间差归因于循环实现；这些结果没有给出满足主样本时间和内存条件的收益。一次只读标准库字节码审阅短暂启动了 javap/JVM，也属于背景活动。

六轮均退出 3、1032 个错误、71047 个 Java 文件，不能把退出 3 当作成功。三对均无文件缺失/新增、无 JADX ERROR 行变化；Java token 变化分别为 **96/83/85** 类。A 占位方法数为 914/906/915，B 为 910/902/911。B 相对本轮三份 A 的并集多两项（`a.b(boolean)`、`Utils.clearAllSharePreferences`），均在保留的 14 份历史清单中出现，且找到了普通历史 CLI 的整类字节相同版本：

- B1 的 `com.efs.sdk.base.newsharedpreferences.a` 与 usage 修复前 A2/A3 相同（`20f2d41`）。
- B1 的 `com.taobao.accs.utl.Utils` 与 usage 修复 B1 相同（`0bf6c9d`）；B3 的另一整类变体与 usage A3、B2/B3 相同。

这些匹配不覆盖其余 token 差异，也不证明候选不影响失败概率或整体语义等价。完整 diff、逐方法清单及比较对象保存在原始目录，摘要见 [type-bound-selection-results.json](type-bound-selection-results.json)。

主样本筛选已失败，因此没有继续独立代表 APK 扩测或候选 JFR；不能宣称候选实际减少了多少总分配。两处生产方法及本轮测试均已恢复，保留源码与 `0bf6c9d` 一致，重新 `installDist`。完整候选和 18 项回归归档为 [可重新应用的 patch](type-bound-selection-candidate.patch)，零上下文 patch SHA256 `2bc932933c7bbfd127b5b0d0f96c21a3c67cff949097d4ca6fa403da517b67d3`（应用时使用 `git apply --unidiff-zero`）；1151 项记录仅对应归档候选，保留修复的检查仍是 1133 项。没有把此次候选提交为性能优化。

若以后重新评估，回归还可补三个非 null 类型的非传递比较器链（INT、OBJECT、LONG 应选 LONG）及 comparator 本身抛出后的停止读取；本轮已有 getter 抛出检查，不把两者混同。


## 未采用的后续捷径

本轮只读复核确认了几个不能直接实施的方案：

- 把 bounds 成员复制到数组会冻结集合成员观察，并改变 fail-fast 行为；预取动态 type 还改变 getter/比较的交错。不因 iterator 的分配权重直接接受缓存。
- 省略重复候选检查可能改变动态 getter、日志、异常和候选数量停止条件。
- TypeUpdateInfo 的 IdentityHashMap 默认在构造时分配数组。旧诊断约 48.53% 实例从未登记更新，但本轮没有峰值同时存在条目数分布。小初始表在 map 较大时会额外扩容，最终 size 与 updateSeq 又不能替代峰值（rollback 会删除且不重置序号）。先前惰性分配试验已因算法优先级搁置，本轮没有重新实施。
- 使用关系 root 锁中 FieldNode.addUseIn 的 JFR 阈值事件为 144 次、累计 8.7866 线程秒。等待跨线程重叠，不能当作可消除的 wall。字段单独锁可作为独立研究，方法反向引用仍需统一保护；不能换成 ClassNode.this，类处理锁顺序有死锁风险。

未检查 GUI/外部插件/任意并发深重载、完整输出重编译；APK 整体语义等价仍未成立。没有把本轮试验叠加成新的累计性能收益。
