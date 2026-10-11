# 依赖诊断输出差异：逐项复核

> 后续已实现 [共享使用关系并发修复](INLINE_USAGE_FIX.md)：旧路径五项受控回归失败，修复后六项通过，完整 core/CLI 报告 1133 项、0 failures/errors、1 条件跳过。历史 APK 的实际写入者及精确 `ArrayList.add` 越界仍未受控重现；其他生命周期/字段列表竞态与整体语义等价仍开放。[后续类型候选](TYPE_BOUND_SELECTION.md) 因主样本未得收益撤回。下文保留本阶段的原始结论。

本轮接续 [依赖诊断结论](DEPENDENCY_PROFILE_FINDINGS.md)，先解释六个新增占位方法。五个异常区域占位体已经在未插桩 A 中复现，且**整个类的 Java token** 与对应 B 输出相同；第六个内联错误仍未闭合。不能据此宣布插桩版本语义等价或获得新的性能收益。

原始记录位于本机 `out/jadx-perf/20261011/profile-quality-audit`。结构化证据、文件哈希及检查结果见 [dependency-profile-quality-audit.json](dependency-profile-quality-audit.json)。历史 A/B 原始测量不修改。

## 五个异常区域占位体

使用冻结的 A=`7203687` 分发包、原千问 7.3.6 APK、JDK 27、16 GiB heap 和 8 线程设置。`ReproduceFinallyHash` 在反编译前以不同次数调用 `System.identityHashCode(new Object())`，再固定四个 `TryEdgeType` 的哈希；不改 jadx 字节码、visitor 或输入。每个 JVM 依次生成下表五个类，顺序固定。这是控制类生成 API 的诊断，未执行全量 CLI，不进入性能中位数。

| 方法 | 出现占位体的 priming | 完整类 token 匹配的历史输出 |
| --- | ---: | --- |
| `Circle.setHoleOptions(List)` | 0、2 | B1 |
| `newsharedpreferences.a.b(boolean)` | 0、3 | B1 |
| `Utils.clearAllSharePreferences(Context)` | 3 | B2、B3 |
| `PrerenderManager.releaseAttachedPrerender(ICompassWebView)` | 2 | B3 |
| `RecommendConfig.ULiangData.DataFetchTask.run()` | 0、1、2、3 | B1 |

以上均为 `performance/20261010/audit.py` 去掉注释后的**整类 token 精确比较**，没有去掉方法体或只比占位字符串。Circle、共享偏好、Utils 和 PrerenderManager 的其他 priming 也得到与历史 A 完整类 token 相同的版本。RecommendConfig 的四次控制均为占位体，未在该控制中复现其完整方法体。

`TryEdge.hashCode` 包含枚举对象，多层 `HashMap` 的遍历顺序因此可能变化。相同字节码和合法哈希次序足以让原路径输出不同；这不是格式差异，也不是错误输出正确的证明。诊断插桩仍可能改变哈希初始化或调度，不能承诺它对所有输出无影响。

四次完成的批量控制退出均为 0，每次保存五个类；API 进程退出 0 不代表没有反编译警告。首次 `finally-batch-1` 被会话中断，没有完整退出记录，排除；确认对应 JVM 已结束后，单独完成 `finally-batch-1-retry`。另有先行的 Circle 单类 priming 0/1 对照。

## 内联错误仍未闭合

B3 的 `statuscontainer.d.invoke` 错误栈为：

```text
ArrayList.add
ListUtils.safeReplace:157
InlineMethods.replaceClsUsage:193
InlineMethods.updateUsageInfo
```

这里向目标类的 `useInMth` 追加引用。`ClassNode` 直接发布普通 `ArrayList`；处理线程只持有当前类的监视器，其他类的线程仍可同时更新同一目标列表。冻结 A/B 的 `ListUtils`、`InlineMethods` 以及四个相关异常区域类的字节码逐字节相同。

`ReproduceUsageListRace` 在冻结 A 的原 helper 上比较串行与八线程写同一非空列表，保留了两个构造条件、各两次运行的全部结果：

| 构造条件 | 串行项数 | 默认 JIT / `-Xint` 尝试数 | 项数不符的轮数 | 越界栈 |
| --- | ---: | ---: | ---: | --- |
| 初始 1 项，每线程追加 512 项 | 4097 | 各 100 | 100 / 100 | 两次均出现 `ArrayList.indexOfRange` |
| 初始 size/capacity 为 82，每线程追加 1 项 | 90 | 各 10000 | 20 / 50 | 均未观察到 |

第一版源码保存在原始记录的 `usage-list-wide-stress-source.java`，SHA256 为 `eb29e09e6586f0c8d04a6d99f9b692b3c7b9ea8fb886247f1bf6224e4c6737c2`；仓库当前源码对应第二行。82/82 是根据原异常索引 83、数组长度 82 构造的扩容条件，原异常并不能证明竞争开始时就是这个容量和大小。四次均要求捕获 `ArrayList.add` 越界栈，因此均退出 1；它们都**没有复现这个精确异常位置，更没有复现目标类的完整内联过程**。

这证明原 helper 可因无同步共享访问而损坏列表；B3 与此机制相符。尚未识别 B3 的两个实际写入者，插桩影响其发生概率的可能性仍在，所以保留这一个未解决的质量差异。

没有用复制列表或增加类锁来掩盖错误。复制再发布可能丢更新；锁另一个类的 `classInfo` 或类对象可能与已有处理锁形成相反的加锁次序。方法/字段的使用列表、`MethodNode.setUseIn` 对调用者 `methodsUsed` 的回写和外部读者也需要共同确定所有权。

## 独立的异常作用域缺陷

审计发现 `TryEdgeScopeGroupMap.getScopeEnds` 遇到已经分组的边时用 `break` 结束整个待处理列表，从而遗漏后续独立出口。下游 `TryCatchEdgeBlockMap` 只构建已返回分组的范围；遗漏出口还可能使 finally 重复代码检查面对不完整的范围集合。

最小修正仅将这一处 `break` 改为 `continue`，仍执行全部分析、保留原异常处理与后续分组合并。没有改哈希函数或容器来固定某一种偶然次序。

新增回归构造两条汇合于 J 的边和另一条独立终止于 K 的边，按实际 key 顺序将独立边设为首项，确保不同枚举哈希下均触发旧缺陷。旧实现实跑 1 项失败，原因正是缺少 K；修正后的定向 try/catch、同步块及新回归共 94 项通过，core 格式和主/测试 Checkstyle 通过。独立边在中间列表中出现两次，断言同时检查最终不重复。

这个回归直接提供作用域关系矩阵，未覆盖关系矩阵构造、合并逻辑或整个 APK 的语义。另将修正后编译得到的唯一 `TryEdgeScopeGroupMap.class` 放在冻结 A 的 classpath 前，按同样 priming 0–3 重做五类控制：四次均完成，20 对完整类 token 全部不变。**这行修复没有修好前述五个占位体**；它修正的是独立的漏出口缺陷，不能充当这些 APK 输出的根因闭合。

随后完整 core/CLI 检查共 1127 项，0 failures/errors、1 既有条件跳过；两模块格式、主/测试 Checkstyle 和 `installDist` 成功。冻结修正版分发包与旧 B 的 62 个文件相比，仅 core JAR 不同；逐项检查 JAR 内容，唯一变化是 `TryEdgeScopeGroupMap.class`。完整测试 XML 另行归档并保留 SHA256。

最后用该分发包运行一次相同 APK、JDK、heap、8 线程的全量 CLI 筛查：退出 3，1032 个错误，71047 个 Java 文件，无缺失或新增；911 个占位方法均已在此前七份输出的并集中出现。与 B1/B2/B3 分别有 91/103/95 个文件的 Java token 不同；新增占位方法相对单轮分别为 5/11/8 个。错误标记变化为 `GridLayoutManager` 的 RegionMaker 溢出类型变化，以及相对 B3 不再出现该内联错误。一次未出现不能证明竞争已经修复，全部 token 差异也尚未人工语义审完。

该次筛查 wall 128.41 秒、进程 CPU 781.87 秒、peak RSS 15992172 KiB，仅记录完整性；它不是交错重复性能对照，不计算提速。完整清单、逐方法差异、三份统一 diff、退出码和主机活动均已保留。

## 后续性能切口

重复 `FixTypesVisitor` 的 39.230 CPU 秒包含整个 resolver 序列、重新构建 bounds、类型传播和多变量搜索。SSA、实参、cast、PHI 和泛型元数据都会变化，当前证据不支持跨 pass 缓存或省略第二轮处理。

下一步先分解 resolver 和 `TypeSearch` 的成本，再考虑同一 invoke 插入多个 cast 时局部复用所属 block 查询。不能缓存指令位置，因为每次插入都会改变位置。此次未实施类型推断优化，也不把已知的小扫描冗余写成可观收益。

## 复现控制

下面各进程串行运行，输出目录必须是新的。原始 `baseline-artifact.json` 应先与分发包的全部文件哈希核对；本轮已核对。源码比较使用原始完整输出及 `audit.tokens`，不能仅根据 runner 的类级 `omitted` 字段判断某个具体方法。

```bash
JADX_AUDIT_BASE="$PWD/out/jadx-perf/20261011/dependency-profile/baseline"
JADX_AUDIT_OUT="$PWD/out/jadx-perf/quality-reproduce"
mkdir -p "$JADX_AUDIT_OUT/classes"
out/jadx-perf/20261010/toolchains/jdk-11.0.32.1+1/bin/javac --release 11 \
  -cp "$JADX_AUDIT_BASE/lib/*" -d "$JADX_AUDIT_OUT/classes" \
  performance/20261010/ReproduceFinallyHash.java \
  performance/20261011/ReproduceUsageListRace.java
JADX_AUDIT_TARGETS='com.amap.api.maps.model.Circle,com.efs.sdk.base.newsharedpreferences.a,com.taobao.accs.utl.Utils,com.uc.compass.preheat.PrerenderManager,com.uc.picturemode.pictureviewer.interfaces.RecommendConfig'
for JADX_AUDIT_PRIMING in 0 1 2 3; do
  env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS -u JAVA_OPTS -u JADX_OPTS \
    /usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
    -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
    -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US \
    -cp "$JADX_AUDIT_OUT/classes:$JADX_AUDIT_BASE/lib/*" \
    ReproduceFinallyHash out/jadx-perf/20261010/corpus/qwen-7.3.6.apk \
    "$JADX_AUDIT_TARGETS" "$JADX_AUDIT_PRIMING" "$JADX_AUDIT_OUT/$JADX_AUDIT_PRIMING"
done
```

共享列表压力诊断使用同一 classpath，执行 `ReproduceUsageListRace`，外部限制 60 秒；另一次在 Java 参数中加入 `-Xint`。当前源码每次最多尝试 10000 轮，本轮两次均退出 1，不能当作通过的回归。若复核 4097 项的早期实验，将原始记录中的 `usage-list-wide-stress-source.java` 复制到单独目录并命名为 `ReproduceUsageListRace.java` 后再编译；不要混用两个版本的源码哈希或结果。

未完成：原 B3 内联错误的完整重现与修复、插桩整体等价证明、新正式性能基线、GUI/插件/深重载覆盖和完整源码重编译。此次没有新的性能收益可验收。
