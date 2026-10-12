.class public Ltrycatch/TestCatchPhiNoArgs;
.super Ljava/lang/Object;

.field public static failures:I
.field public static lastError:Ljava/lang/String;
.field public static mode:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static prepare()I
    .registers 2
    sget v0, Ltrycatch/TestCatchPhiNoArgs;->mode:I
    const/4 v1, 0x1
    if-eq v0, v1, :throw_
    const/4 v0, 0x7
    return v0
    :throw_
    new-instance v0, Ljava/io/IOException;
    const-string v1, "prepare"
    invoke-direct {v0, v1}, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
    throw v0
.end method

.method public static finish(I)I
    .registers 3
    sget v0, Ltrycatch/TestCatchPhiNoArgs;->mode:I
    const/4 v1, 0x2
    if-eq v0, v1, :throw_
    return v2
    :throw_
    new-instance v0, Ljava/io/IOException;
    const-string v1, "finish"
    invoke-direct {v0, v1}, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
    throw v0
.end method

.method public static run()I
    .registers 4
    :prepare_start
    invoke-static {}, Ltrycatch/TestCatchPhiNoArgs;->prepare()I
    move-result v0
    :prepare_end
    .catch Ljava/io/IOException; {:prepare_start .. :prepare_end} :prepare_failed

    :finish_start
    invoke-static {v0}, Ltrycatch/TestCatchPhiNoArgs;->finish(I)I
    move-result v0
    :finish_end
    .catch Ljava/io/IOException; {:finish_start .. :finish_end} :finish_failed
    return v0

    :finish_failed
    move-exception v1
    goto :failed
    :prepare_failed
    move-exception v1
    :failed
    invoke-virtual {v1}, Ljava/io/IOException;->getMessage()Ljava/lang/String;
    move-result-object v2
    sput-object v2, Ltrycatch/TestCatchPhiNoArgs;->lastError:Ljava/lang/String;
    sget v2, Ltrycatch/TestCatchPhiNoArgs;->failures:I
    add-int/lit8 v2, v2, 0x1
    sput v2, Ltrycatch/TestCatchPhiNoArgs;->failures:I
    const/4 v2, -0x1
    return v2
.end method

.method public check()V
    .registers 5
    const/4 v0, 0x0
    sput v0, Ltrycatch/TestCatchPhiNoArgs;->failures:I
    const/4 v1, 0x0
    sput-object v1, Ltrycatch/TestCatchPhiNoArgs;->lastError:Ljava/lang/String;

    const/4 v0, 0x0
    sput v0, Ltrycatch/TestCatchPhiNoArgs;->mode:I
    invoke-static {}, Ltrycatch/TestCatchPhiNoArgs;->run()I
    move-result v2
    const/4 v3, 0x7
    if-ne v2, v3, :failed
    sget v2, Ltrycatch/TestCatchPhiNoArgs;->failures:I
    if-nez v2, :failed

    const/4 v0, 0x1
    sput v0, Ltrycatch/TestCatchPhiNoArgs;->mode:I
    invoke-static {}, Ltrycatch/TestCatchPhiNoArgs;->run()I
    move-result v2
    const/4 v3, -0x1
    if-ne v2, v3, :failed
    sget v2, Ltrycatch/TestCatchPhiNoArgs;->failures:I
    const/4 v3, 0x1
    if-ne v2, v3, :failed
    const-string v2, "prepare"
    sget-object v3, Ltrycatch/TestCatchPhiNoArgs;->lastError:Ljava/lang/String;
    invoke-virtual {v2, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v2
    if-eqz v2, :failed

    const/4 v0, 0x2
    sput v0, Ltrycatch/TestCatchPhiNoArgs;->mode:I
    invoke-static {}, Ltrycatch/TestCatchPhiNoArgs;->run()I
    move-result v2
    const/4 v3, -0x1
    if-ne v2, v3, :failed
    sget v2, Ltrycatch/TestCatchPhiNoArgs;->failures:I
    const/4 v3, 0x2
    if-ne v2, v3, :failed
    const-string v2, "finish"
    sget-object v3, Ltrycatch/TestCatchPhiNoArgs;->lastError:Ljava/lang/String;
    invoke-virtual {v2, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v2
    if-eqz v2, :failed
    return-void

    :failed
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
