# jadx 性能测量复现

测量覆盖 APK 的全部 Java 类，包含加载、分析、生成和写出源码。资源输出关闭，反编译线程固定为 8。每次启动新的 JVM，写入新目录；输入和分发包在计时前计算 SHA256，输出在计时结束后逐文件计算 SHA256。

## 固定环境

本机为 Linux x86_64，AMD Ryzen 7 7840H，8 核 16 线程，约 58 GiB 内存。性能运行使用 `/usr/lib/jvm/java-27-openjdk/bin/java`，其版本输出为 OpenJDK 27 build 27。Gradle 9.7.1 使用 Temurin 25.0.4.1 构建，Java 编译和测试使用 Temurin 11.0.32.1 工具链。Gradle 的 Groovy 在本机 JDK 27 下无法启动，因此构建 JVM 与性能运行 JVM 分开固定。

内核为 Linux 7.2.8-arch1-2。两份 Temurin 工具链的固定下载地址、字节数及已验证 SHA256 在 [toolchains.tsv](toolchains.tsv)；解压后设置下文对应路径。性能运行 JDK 必须固定为同一构建，不能在 A/B 之间替换为另一个版本。

性能 JDK 的 Arch 包版本为 `jdk-openjdk 27.u35-1`，安装包保存在原始结果目录的 `toolchains/jdk-openjdk-27.u35-1-x86_64.pkg.tar.zst`。[runtime.tsv](runtime.tsv) 保存安装包和关键运行文件的 SHA256、字节数及本地来源；`bin/java`、`libjvm.so`、`lib/modules` 和 `release` 已与安装包内对应文件逐字节哈希对上。它补充版本字符串，便于保留同一运行环境。

JVM 参数固定为 `-Xms256m -Xmx16g -XX:ParallelGCThreads=3`，另有 ZIP 兼容参数、UTF8 和英文区域设置。完整命令保存在每次运行的 `metadata.json`，见 [measure.py](measure.py)。脚本会移除环境中的隐式 JVM 参数，记录主机 CPU 活动、退出码、标准输出和标准错误。

## 在同一工作目录构建两个版本

先提交或保存自己的修改。下面的命令会切换当前 checkout；基线和优化版使用同一目录、同一编译环境。`out/jadx-perf/reproduce` 必须尚不存在。镜像 init 脚本只替换仓库地址，依赖版本保持仓库定义；正常访问 Maven Central 时可在两次构建中同时省略该选项。

```bash
set -euo pipefail
cd /home/ltlly/Code/jadx-perf
test -z "$(git status --porcelain)"
JADX_PERF_OUT="$PWD/out/jadx-perf/reproduce"
test ! -e "$JADX_PERF_OUT"
mkdir -p "$JADX_PERF_OUT"
cp -a performance/20261010 "$JADX_PERF_OUT/tools"
JADX_PERF_BUILD_JDK="$PWD/out/jadx-perf/20261010/toolchains/jdk-25.0.4.1+1"
JADX_PERF_JAVA11="$PWD/out/jadx-perf/20261010/toolchains/jdk-11.0.32.1+1"

git switch --detach 8e35efc4e156c20d2af40c0a160eb78cdcfe9f40
JAVA_HOME="$JADX_PERF_BUILD_JDK" JADX_BUILD_JAVA_VERSION=11 ./gradlew \
  -Dorg.gradle.java.installations.paths="$JADX_PERF_JAVA11" \
  --init-script "$JADX_PERF_OUT/tools/mirrors.init.gradle" --max-workers=8 \
  :jadx-core:clean :jadx-cli:clean :jadx-cli:installDist --console=plain
cp -a jadx-cli/build/install/jadx "$JADX_PERF_OUT/baseline"

git switch --detach 26552586d64e43b0a4806f6cc70c5a2202731dd1
JAVA_HOME="$JADX_PERF_BUILD_JDK" JADX_BUILD_JAVA_VERSION=11 ./gradlew \
  -Dorg.gradle.java.installations.paths="$JADX_PERF_JAVA11" \
  --init-script "$JADX_PERF_OUT/tools/mirrors.init.gradle" --max-workers=8 \
  :jadx-core:clean :jadx-cli:clean :jadx-cli:installDist --console=plain
cp -a jadx-cli/build/install/jadx "$JADX_PERF_OUT/optimized"
git switch codex/jadx-performance
```

这里的 A 是共同正确性基线：在原始 `4574686` 上只修复共享三元条件和副本 SSA 清理；B 在相同修复上增加六项性能优化。不能用旧原始 JAR 与新 B 混合计算最终收益。各阶段提交及本次构建的 core JAR SHA256 见 [builds.tsv](builds.tsv)。复现某项独立收益时，依次构建它的父提交和该提交，再用相同命令交错运行。每一阶段后单独采集全量 JFR，不与正式运行并行。

七个源代码提交另导出为 `/home/ltlly/Code/jadx-perf/out/jadx-perf/20261010/verified-patches` 中的独立 patch 和 `jadx-performance.mbox`，哈希见 [patches.tsv](patches.tsv)。从原始 `4574686` 开始应用全部七项；第一项是共同正确性修复，后六项才是性能改动。报告和测量脚本单独提交。

输入的准确字节数、SHA256、DEX 数和来源在 [corpus.tsv](corpus.tsv)。原始 APK 保存在 `out/jadx-perf/20261010/corpus`。下载链接可能更新或失效；必须匹配记录的 SHA256，不能把新版本混入同一组比较。

## 串行交错运行

构建、测试、采样与正式性能运行分开。以下 Python 命令依次执行 A1、B1、A2、B2、A3、B3，并保留每一对的精确内容 diff。Qwen 原版会返回退出码 3，因此脚本保留 Java 的退出码供审计，不能仅根据 Python 命令退出成功判断反编译成功。

```bash
python3 - "$JADX_PERF_OUT" <<'PY'
from pathlib import Path
import subprocess
import sys
root = Path(sys.argv[1])
apk = Path('/home/ltlly/Code/jadx-perf/out/jadx-perf/20261010/corpus/qwen-7.3.6.apk')
for iteration in range(1, 4):
    for label, distribution in [('A', 'baseline'), ('B', 'optimized')]:
        subprocess.run(['python3', str(root/'tools/measure.py'), str(root/distribution),
                        str(apk), str(root/f'qwen-{label}{iteration}')], check=True)
    subprocess.run(['python3', str(root/'tools/audit.py'),
                    str(root/f'qwen-A{iteration}'), str(root/f'qwen-B{iteration}'),
                    str(root/f'audit-{iteration}')], check=True)
subprocess.run(['python3', str(root/'tools/quality.py'), str(root/'qwen-A1')]
               + [str(root/f'qwen-{label}{i}') for i in range(1, 4) for label in 'AB'
                  if (label, i) != ('A', 1)], check=True)
PY
```

`metrics.json` 的 `wall_s` 是全进程耗时，CPU 消耗为 `user_s + sys_s`，`rss_kib` 为 `/usr/bin/time` 的峰值 RSS，单位 KiB。每组三次报告中位数与最小值、最大值。各优化的增量比较不能相加替代最终基线比较。每个 APK 都必须使用自身的基线输出作为质量参照。

`audit.py` 检查文件缺失、新增和逐字节差异，保存完整 unified diff，再用词法分组区分注释差异与 Java token 差异。词法分组用于定位问题，不构成语义等价证明。`quality.py` 另外保存逐方法的占位体清单、新增占位体和恢复方法体，不能只比较文件数或总错误数。

完整十样本复现可使用下列驱动。它先验证输入 SHA256，串行运行各 APK 的三对 A/B，保存每对内容 diff、同一版本重复运行的 diff 和逐方法占位体检查。已有目录会报错；如需分批，使用 `--names` 指定 corpus.tsv 中的名称，每个名称只运行一次。

```bash
python3 "$JADX_PERF_OUT/tools/benchmark.py" "$JADX_PERF_OUT/baseline" \
  "$JADX_PERF_OUT/optimized" out/jadx-perf/20261010/corpus "$JADX_PERF_OUT/corpus-runs"
python3 "$JADX_PERF_OUT/tools/summarize_results.py" "$JADX_PERF_OUT/corpus-runs" \
  --output "$JADX_PERF_OUT/measurements.tsv"
python3 "$JADX_PERF_OUT/tools/summarize_quality.py" "$JADX_PERF_OUT/corpus-runs" \
  --output "$JADX_PERF_OUT/quality.tsv"
python3 "$JADX_PERF_OUT/tools/selfcheck.py"
```

`quality.tsv` 汇总缺失文件、源码差异、错误、警告和逐方法占位体；`quality.details.json` 保留新增变体的具体文件与方法。额外列出修改版所有运行的占位体并集与基线并集之差，避免只检查稳定失败而漏掉偶发失败。新增 token/警告变体是人工复核入口，脚本不会自动将其判为安全。`--names` 可用于提前复核已经完成六次运行的样本。

回归及格式检查使用相同 Java 11 工具链：

```bash
JAVA_HOME="$JADX_PERF_BUILD_JDK" JADX_BUILD_JAVA_VERSION=11 ./gradlew \
  -Dorg.gradle.java.installations.paths="$JADX_PERF_JAVA11" \
  --init-script "$JADX_PERF_OUT/tools/mirrors.init.gradle" --max-workers=8 \
  :jadx-core:test :jadx-cli:test \
  :jadx-core:spotlessJavaCheck :jadx-core:checkstyleMain :jadx-core:checkstyleTest \
  :jadx-cli:spotlessJavaCheck :jadx-cli:checkstyleMain :jadx-cli:checkstyleTest --console=plain
```

## 全量采样与语义诊断

采样单独运行，不计入正式中位数。JFR 栈深度固定为 256；分配权重是抽样估计，包含递归的方法在单个样本的 inclusive 统计中只计算一次。JSON 转储用流式解析，避免把数 GiB 的展开栈一次载入内存。

```bash
python3 "$JADX_PERF_OUT/tools/measure.py" "$JADX_PERF_OUT/optimized" \
  out/jadx-perf/20261010/corpus/qwen-7.3.6.apk "$JADX_PERF_OUT/profile" --profile
python3 "$JADX_PERF_OUT/tools/summarize_profile.py" "$JADX_PERF_OUT/profile/profile.jfr"
```

`make_shadow.py` 从已知旧提交和当前源码构造独立的诊断分发包，在同一份运行时输入上执行新旧检查，记录返回结果是否一致。诊断版增加旧算法计算和计数，其耗时不得作为优化版性能。生产源码和生产 JAR 不包含这些计数器。

```bash
python3 "$JADX_PERF_OUT/tools/make_shadow.py" "$JADX_PERF_OUT/optimized" \
  "$JADX_PERF_OUT/shadow" "$JADX_PERF_JAVA11"
python3 "$JADX_PERF_OUT/tools/measure.py" "$JADX_PERF_OUT/shadow" \
  out/jadx-perf/20261010/corpus/qwen-7.3.6.apk "$JADX_PERF_OUT/shadow-qwen"
```

[ReproduceDependencyUnload.java](ReproduceDependencyUnload.java) 重放依赖处理与消费者代码生成之间的一个卸载顺序。它是既有生命周期错误的控制实验，并非一次正常的全量 CLI 性能运行。默认消费者为 `j0`；可用第三个参数指定 `h0` 或 `i0`，共同正确性基线也已实际运行这些对照。下列命令使用原始 `4574686` 的分发包，本机保存在 `out/jadx-perf/20261010/artifacts/baseline`。需要重新构建时，在上面的同一 checkout 构建流程中使用原始提交并保存为 `original`。默认参数掩码污染的原版对照必须使用未修复版本。

```bash
JADX_PERF_ORIGINAL="$PWD/out/jadx-perf/20261010/artifacts/baseline"
"$JADX_PERF_JAVA11/bin/javac" --release 11 -cp "$JADX_PERF_ORIGINAL/lib/*" \
  -d "$JADX_PERF_OUT/repro-classes" "$JADX_PERF_OUT/tools/ReproduceDependencyUnload.java"
/usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
  -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
  -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
  ReproduceDependencyUnload out/jadx-perf/20261010/corpus/qwen-7.3.6.apk "$JADX_PERF_OUT/unload-repro"
```

[ReproduceVisibilityOrder.java](ReproduceVisibilityOrder.java) 用原版的正常类生成 API 对照两个顺序，重现 DuckDuckGo 的 `public`/`protected` 输出差异：

```bash
"$JADX_PERF_JAVA11/bin/javac" --release 11 -cp "$JADX_PERF_ORIGINAL/lib/*" \
  -d "$JADX_PERF_OUT/repro-classes" "$JADX_PERF_OUT/tools/ReproduceVisibilityOrder.java"
/usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
  -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
  -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
  ReproduceVisibilityOrder out/jadx-perf/20261010/corpus/duckduckgo-5.297.1.apk "$JADX_PERF_OUT/visibility-repro"
```

[ReproduceFlowStateRace.java](ReproduceFlowStateRace.java) 在全部真实 visitor 执行后、依赖发布 `PROCESS_COMPLETE` 前，用 latch 固定一个并发窗口。原始提交和共同正确性基线均在 `false` 对照下生成完整方法体，在 `true` 下复现同一 Flow `invokeSuspend`、同一 `nullArrayFactory$1.INSTANCE` 指令的 `ensureProcessed` 失败和占位体。它显式调用 core 重载/生成入口，属于受控生命周期对照，并非普通全量 CLI；诊断 visitor 不替换或跳过真实分析。30 秒等待只用于协调实验线程，不是反编译超时。最终基线三轮常规 CLI 没有自然出现这条错误，优化版 B1 出现一次；报告保留此限制。

```bash
"$JADX_PERF_JAVA11/bin/javac" --release 11 -cp "$JADX_PERF_ORIGINAL/lib/*" \
  -d "$JADX_PERF_OUT/repro-classes" "$JADX_PERF_OUT/tools/ReproduceFlowStateRace.java"
for JADX_PERF_CASE in false true; do
  /usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
    -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
    -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
    ReproduceFlowStateRace out/jadx-perf/20261010/corpus/qwen-7.3.6.apk \
    "$JADX_PERF_OUT/flow-state/$JADX_PERF_CASE.java" "$JADX_PERF_CASE"
done
```

此前用普通八线程类生成 API 对 Flow 包做过十个 shuffle seed 对照，未复现这条错误；单纯生成依赖和单纯调用 reload 也未复现。失败尝试不作为质量通过证据，记录保存在原始实验目录中。

[ReproduceThrowsStateRace.java](ReproduceThrowsStateRace.java) 固定另一个已有并发窗口：一个线程刚完成 `BlockSplitter`，尚未由 `BlockProcessor` 附加块级 catch 属性，另一个线程递归推断该类的 throws。原始版、共同基线和最终版均已完成 false/true 两种对照；false 不给 Telegram `AndroidUtilities.doSafe` 添加三种异常，true 添加 `ExecutionException, InterruptedException, TimeoutException`，与全量 B1 相同。所有真实 visitor 最终都执行完。此实验解释具体声明的来源，不代表已在普通全量基线自然复现，也不自动证明其他所有 throws 差异。

```bash
"$JADX_PERF_JAVA11/bin/javac" --release 11 -cp "$JADX_PERF_ORIGINAL/lib/*" \
  -d "$JADX_PERF_OUT/repro-classes" "$JADX_PERF_OUT/tools/ReproduceThrowsStateRace.java"
for JADX_PERF_CASE in false true; do
  /usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
    -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
    -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
    ReproduceThrowsStateRace out/jadx-perf/20261010/corpus/telegram.apk \
    "$JADX_PERF_OUT/throws-state/$JADX_PERF_CASE.java" "$JADX_PERF_CASE"
done
```

[ReproduceClassOrder.java](ReproduceClassOrder.java) 只改变正常类生成 API 的调用顺序，重现默认参数掩码污染。在原版上 target-first 使用正确的 60，target-last 使用错误的 112；共同正确性基线和最终版应当在两种顺序均保留 60。

```bash
"$JADX_PERF_JAVA11/bin/javac" --release 11 -cp "$JADX_PERF_ORIGINAL/lib/*" \
  -d "$JADX_PERF_OUT/repro-classes" "$JADX_PERF_OUT/tools/ReproduceClassOrder.java"
for JADX_PERF_VERSION in original baseline optimized; do
  if [ "$JADX_PERF_VERSION" = original ]; then
    JADX_PERF_DIST="$JADX_PERF_ORIGINAL"
  else
    JADX_PERF_DIST="$JADX_PERF_OUT/$JADX_PERF_VERSION"
  fi
  /usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
    -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
    -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_DIST/lib/*" \
    ReproduceClassOrder out/jadx-perf/20261010/corpus/qwen-7.3.6.apk \
    io.ktor.http.URLBuilderKt \
    kotlin.collections.CollectionsKt___CollectionsKt,kotlin.reflect.jvm.internal.impl.types.SimpleType \
    "$JADX_PERF_OUT/masks/$JADX_PERF_VERSION"
done
```

[ReproduceClassOrder.java](ReproduceClassOrder.java) 还可重现 `throws JSONException` 的加载顺序依赖。以下原版对照中，target-first 的 `h` 无 throws，target-last 有 throws；共同正确性基线也已复现两种声明。它们是普通 API 的类生成顺序对照，不是性能计时，不能混入中位数。

```bash
/usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
  -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
  -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
  ReproduceClassOrder out/jadx-perf/20261010/corpus/qwen-7.3.6.apk \
  com.aliyun.tongyi.livechat.jsapi.LiveChatJsApiManager \
  'com.aliyun.tongyi.livechat.jsapi.LiveChatJsApiManager$startStateObserver$1' \
  "$JADX_PERF_OUT/throws-order-original"
```

[ReproduceFinallyHash.java](ReproduceFinallyHash.java) 在不同合法 enum identity hash 下生成同一类，原版也会出现 finally 识别或占位体差异。它不会修改 jadx 字节码或跳过分析。下面以 Edge 的 `prx` 为例；两个独立 JVM 的 priming 参数分别为 0 和 1。

```bash
"$JADX_PERF_JAVA11/bin/javac" --release 11 -cp "$JADX_PERF_ORIGINAL/lib/*" \
  -d "$JADX_PERF_OUT/repro-classes" "$JADX_PERF_OUT/tools/ReproduceFinallyHash.java"
for JADX_PERF_PRIMING in 0 1; do
  /usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
    -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
    -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
    ReproduceFinallyHash out/jadx-perf/20261010/corpus/edge.apk prx "$JADX_PERF_PRIMING" \
    "$JADX_PERF_OUT/finally/edge-$JADX_PERF_PRIMING.java"
done
```

同一控制还重现了千问 `com.autonavi.aps.amapapi.utils.h.c()` 的实际异常处理差异：priming 0 保留循环内、外两个 catch，priming 11 丢失内层 catch。后者会改变异常后是否继续遍历，不能当作格式差异。共同正确性基线的正式 A2 和基线 profile 也已有相同方法 token。

```bash
for JADX_PERF_PRIMING in 0 11; do
  /usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
    -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
    -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
    ReproduceFinallyHash out/jadx-perf/20261010/corpus/qwen-7.3.6.apk \
    com.autonavi.aps.amapapi.utils.h "$JADX_PERF_PRIMING" \
    "$JADX_PERF_OUT/finally/qwen-h-$JADX_PERF_PRIMING.java"
done
```

十样本复核补充了以下精确方法对照。表中“原始”是 `4574686`，“共同基线”是 `8e35efc`；使用相应分发包，不能互换后声称相同证据。原始 finally 变体可能有实际语义错误，复现只证明来源。

| APK | 原始类名 | 方法 | priming | 分发包 | 对上内容 |
|---|---|---|---:|---|---|
| Edge | `fmf0` | `c` | 1 | 原始 | B2 finally 完整方法 |
| WhatsApp | `X.1ad` | `A0B` | 4 | 原始 | B1 少两处清理调用的完整方法 |
| WhatsApp | `X.QhD` | `A0J` | 0 | 原始 | B2 finally 完整方法 |
| WhatsApp | `X.R0Q` | `CFP` | 5 | 原始 | B3 finally 完整方法 |
| WhatsApp | `X.0kr` | `A0a` | 0 | 共同基线 | B 三轮稳定占位体、同一 handler `0x002c` |

例如复现注册服务方法：

```bash
/usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
  -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
  -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
  ReproduceFinallyHash out/jadx-perf/20261010/corpus/whatsapp.apk X.1ad 4 \
  "$JADX_PERF_OUT/finally/whatsapp-registration.java"
```

普通类生成顺序对照也得到多个整类 token 匹配。下面的两个原版示例分别复现 Signal 缺少 byte cast 的构造调用，以及 Firefox 桥接方法名称变体；第三个参数是先生成的类，两个顺序都会自动运行。

```bash
/usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
  -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
  -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
  ReproduceClassOrder out/jadx-perf/20261010/corpus/signal-8.29.3.apk \
  'androidx.compose.foundation.text.selection.SelectionAdjustment$Companion' \
  'androidx.compose.foundation.text.selection.SelectionAdjustment$Companion$$ExternalSyntheticLambda0' \
  "$JADX_PERF_OUT/selection-byte-order"
/usr/lib/jvm/java-27-openjdk/bin/java -Xms256m -Xmx16g -XX:ParallelGCThreads=3 \
  -Djdk.util.zip.disableZip64ExtraFieldValidation=true --enable-native-access=ALL-UNNAMED \
  -cp "$JADX_PERF_OUT/repro-classes:$JADX_PERF_ORIGINAL/lib/*" \
  ReproduceClassOrder out/jadx-perf/20261010/corpus/firefox.apk \
  mozilla.appservices.merino.FfiConverterOptionalString \
  mozilla.appservices.merino.FfiConverterRustBuffer,mozilla.appservices.merino.FfiConverterTypeCuratedRecommendationLocale \
  "$JADX_PERF_OUT/firefox-bridge-order"
```

VLC `LiveData` 的两个补充顺序仍输出 protected，没有复现 B3 的 public；Firefox `getHostFromUrl` 两个补充顺序均无 throws，也未复现其新声明。失败对照和未逐项证明的声明保留在 [quality-review.json](quality-review.json)，不能把所有普通 API 实验都算作通过。
