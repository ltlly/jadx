# 结构性研究：分离发布态恢复与方法体恢复

日期：2026-10-11。起点：`codex/perf-macos` / `6e44d0218d75dae7ed2bdc8727ec4640231f1367`。
本轮只增加研究回归与夹具，没有修改生产代码、打开依赖摘要或增加缓存，尚无新增全量性能收益。

## 1. 要验证的方向

依赖类生成后卸载，之后的消费者又触发完整分析。目标不是“已生成就跳过”，而是研究：能否在原重入时刻恢复消费者可观察的发布态，同时只为真正需要方法体的路径恢复 IR。

实际链为 `ProcessClass.generateCode` 的依赖循环 → `process(dep, false, METADATA_DEPENDENCY)` → `GENERATED_AND_UNLOADED` → `NOT_LOADED` → `load` → 全部分析 passes → `PROCESS_COMPLETE`。当前摘要默认关闭；开启后也只认证无代码子树。内联的 `forceProcess` 和 `codegenDeps` 是独立的 LEGACY 路径，不能按元数据请求标签放行。

“重复分析 CPU 很多”不能换算为同等 wall 收益；一次读探针也不能证明所有读者或副作用都已覆盖。

## 2. 本轮实测边界

三个检查位于 `jadx-core/src/test/java/jadx/core/dex/nodes/deps/DependencySummaryTest.java`。

| 检查 | 实际结果 | 能证明什么 / 不能证明什么 |
| --- | --- | --- |
| `genericMetadataReadDoesNotRequireBodyRecovery` | 带代码的泛型子类生成并卸载后，重新取得继承映射，值仍为 `String`；属性是重建的新实例；所有方法保持未加载，类仍为 `GENERATED_AND_UNLOADED`，错误/警告计数不变 | 这个有效签名夹具的泛型元数据可独立于方法体恢复；不是完整依赖恢复证明 |
| `retainingCodedDependencyDropsAnnotationEffects` | 旧流程字段 useIn 为两条相同的 `AnnotatedData.read`；直接驻留只有一条。两个类的缓存 Java 输出及错误/警告计数仍相同 | 带代码类的首次驻留结果不等价于晚期重处理；仅比较生成源码会漏掉共享状态差异 |
| `codedAnnotationClassEffectCanBeReplayedWithoutBodyRecovery` | 使用原 `PrepareForCodeGen` 实例的 class 回调，可以恢复这个夹具相同的注解字段 useIn；方法仍未加载、状态仍为 `GENERATED_AND_UNLOADED` | 一个实际发布效果能够与方法体处理分开；没有执行完整 pass 序列，也没有把类标记为恢复完成，不能作为生产快路径 |

驻留相等假设先以两条 useIn 对一条的断言差异被否证，随后固化为检查已知差异的通过测试；没有修改生产行为来让这个假设成立。源码 oracle 在创建第二个 decompiler 之前保存为独立字符串，避免测试基类共享 JadxArgs、重置共享 code cache 后变成自比较。第三个检查的 oracle 是 LEGACY `forceProcess`，实验侧是直接 class 回调，不是正常元数据依赖快路径。

新增两个显式选择的夹具：
- `AnnotatedCodedData.smali`：类注解引用字段，另有带代码的方法，复用现有消费者。
- `GenericDerived.smali`：使用有效泛型父类签名。原 `Derived.smali` 的签名片段拼成 `Ldst/GenericBase;<Ljava/lang/String;>;`，初次测试取得的继承映射为空。新夹具改用 `Ldst/GenericBase<Ljava/lang/String;>;` 并断言实际映射为 String；没有修改旧夹具或把其历史 A/B 匹配当作正确性证明。初次空映射失败是本轮开发观察，最终套件不再包含那个失败断言。两个 Derived 文件定义相同类名，只能按文件列表择一加载。

## 3. 重要源码纠正

`CLASS_TYPE_VARS` 的生产者是 `dex/nodes/utils/TypeUtils.buildClassTypeVarsAttr`，由 `getClassTypeVars` 惰性调用，不是 `GenericTypesVisitor`。签名解析先把泛型数据写入类/方法字段；普通卸载保留这些字段、删除非 pinned 的派生属性。这个 getter 本身不要求 CFG/SSA/region。

因此历史约 1,414 万次该属性读取不能用来证明必须完整重跑方法流水线，也不是 1,414 万次缓存重建或性能成本。

但这并不授权固定缓存或 pin 住属性：`ClassTypeVarsAttr` 暴露内部映射，泛型合并存在可变 map 操作；深卸载会重建类数据，必须区分普通卸载、深重载和新的分析 epoch。本轮未证明这些情况下的复用及并发等价性。

## 4. 结构方案的证明义务

研究候选是把恢复契约分为两部分，而不是保留整个首轮 IR：

1. **发布态恢复**：在原重入时刻、原执行顺序重建签名派生量，并重放必需的权限、throws、隐藏标志、注解使用关系和诊断效果。
2. **方法体恢复**：内联、构造/字段初始化、异常分析等真正读取指令、SSA 或 region 的路径仍要求正确的完整 IR。

当前阻断点不是那张高频读取表，而是必需效果的传递前置闭包：
- `PrepareForCodeGen` 的 class 回调可以只处理注解，但 method 回调依赖并修改 block/region IR。
- `ExtractFieldInit` 读取构造器/clinit，并调用 shrink。
- `CheckRegions` 读取完整区域和基本块，还能新增 root 可见警告，不能省掉第二轮诊断。
- `MethodThrowsVisitor` 的 visited 状态通常存活，但仍重放声明异常合并和非法异常警告。
- `FixAccessModifiers`、内联、匿名类处理会受晚期共享状态影响；字段 useIn 的顺序与重复次数不能擅自去重。

没有闭合这些前置关系前，保持现有 `METHOD_WITH_CODE` 拒收，不能靠更宽的类形状白名单进入快路径，也不能直接设 `PROCESS_COMPLETE` 来掩盖缺失方法体。

## 5. 下一步与止损条件

下一步先做原路径诊断，不实施新的生产快路径：

1. 静态核对每个发布态读者与写者，列出操作、时刻、输入和所需 IR 阶段；动态事件对照用于验证覆盖，零事件不能替代证明。
2. 在固定千问输入上保留全部 pass，按重入原因记录加载、各 pass、等待和真正跨类方法体读取的成本。区分包含计时与自身计时，避免重复相加；带探针运行不进入正式 A/B。
3. 按可消除的重分析成本加权覆盖率，而非类数或 getter 次数，选择一个可证伪切口。先比较发布态、方法体读者、诊断的顺序/多重性及输出，再测收益和内存。
4. 若必要发布效果的闭包仍覆盖大部分完整流水线，就停止这个版本的方案，不继续放宽门禁。未知插件、重载、未覆盖 IR 读者及异常路径必须在改变状态前回退原流程。
5. 只有候选通过上述检查，才在同一 checkout、输入、JDK、heap、8 线程下进行至少三轮交错 A/B；不能把本轮小夹具结果写成 APK 级收益或语义等价。

## 6. 已运行与未运行

本轮先跑原 `DependencySummaryTest`：14 项通过。新增三项边界检查后，依赖相关三个测试类共 **23 项，0 失败、0 错误、0 跳过**。core Java 格式检查、main/test Checkstyle、`git diff --check` 通过。摘要及源码 SHA256 见 [dependency-recovery-checks.json](dependency-recovery-checks.json)。

复现（Gradle 运行 JDK 与 Java 11 构建工具链需就绪）：

```bash
JADX_BUILD_JAVA_VERSION=11 ./gradlew --no-parallel --max-workers=2 \
  :jadx-core:test --tests 'jadx.core.dex.nodes.deps.*' \
  :jadx-core:spotlessJavaCheck :jadx-core:checkstyleMain :jadx-core:checkstyleTest
```

离线首跑因缺少构建脚本依赖而未进入测试；第一次联网构建因客户端连接中断取消；正常联网重跑后测试执行成功。没有更改依赖版本或构建配置。

未运行全量 core/CLI 测试、APK A/B、JFR、GUI、深重载/并发下新恢复方案或可重编译检查。原始测试 XML/构建日志不随本摘要入库，需重跑验证。**本轮确定的是两个可拆分的效果及一个驻留反例，不是完整结构性突破已经成立，更不是达成 50% 的证明。**
