.class public Ltrycatch/TestSharedCatchCondition;
.super Ljava/lang/Object;

.field public static failures:I
.field public static lastError:Ljava/lang/String;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static prepare(Z)I
    .registers 3
    if-eqz p0, :success
    new-instance v0, Ljava/io/IOException;
    const-string v1, "prepare"
    invoke-direct {v0, v1}, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
    throw v0
    :success
    const/4 v0, 0x7
    return v0
.end method

.method public static finish(IZ)I
    .registers 4
    if-eqz p1, :success
    new-instance v0, Ljava/io/IOException;
    const-string v1, "finish"
    invoke-direct {v0, v1}, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
    throw v0
    :success
    return p0
.end method

.method public static run(IZZZ)I
    .registers 8
    :prepare_start
    invoke-static {p1}, Ltrycatch/TestSharedCatchCondition;->prepare(Z)I
    move-result v0
    :prepare_end
    .catch Ljava/io/IOException; {:prepare_start .. :prepare_end} :prepare_failed

    move v1, p0
    :finish_start
    invoke-static {v0, p2}, Ltrycatch/TestSharedCatchCondition;->finish(IZ)I
    move-result v0
    :finish_end
    .catch Ljava/io/IOException; {:finish_start .. :finish_end} :finish_failed
    return v0

    :finish_failed
    move-exception v2
    goto :failed
    :prepare_failed
    move-exception v2
    move v1, p0
    :failed
    if-eqz p3, :fallback
    invoke-virtual {v2}, Ljava/io/IOException;->getMessage()Ljava/lang/String;
    move-result-object v3
    sput-object v3, Ltrycatch/TestSharedCatchCondition;->lastError:Ljava/lang/String;
    sget v3, Ltrycatch/TestSharedCatchCondition;->failures:I
    add-int/lit8 v3, v3, 0x1
    sput v3, Ltrycatch/TestSharedCatchCondition;->failures:I
    return v1
    :fallback
    return v1
.end method

.method public check()V
    .registers 7
    const/16 v0, 0xc
    const/4 v1, 0x0
    const/4 v2, 0x1
    sput v1, Ltrycatch/TestSharedCatchCondition;->failures:I

    invoke-static {v0, v1, v1, v1}, Ltrycatch/TestSharedCatchCondition;->run(IZZZ)I
    move-result v3
    const/16 v4, 0x7
    if-ne v3, v4, :failed
    sget v3, Ltrycatch/TestSharedCatchCondition;->failures:I
    const/4 v4, 0x0
    if-ne v3, v4, :failed

    invoke-static {v0, v1, v1, v2}, Ltrycatch/TestSharedCatchCondition;->run(IZZZ)I
    move-result v3
    const/16 v4, 0x7
    if-ne v3, v4, :failed
    sget v3, Ltrycatch/TestSharedCatchCondition;->failures:I
    const/4 v4, 0x0
    if-ne v3, v4, :failed

    invoke-static {v0, v1, v2, v1}, Ltrycatch/TestSharedCatchCondition;->run(IZZZ)I
    move-result v3
    const/16 v4, 0xc
    if-ne v3, v4, :failed
    sget v3, Ltrycatch/TestSharedCatchCondition;->failures:I
    const/4 v4, 0x0
    if-ne v3, v4, :failed

    invoke-static {v0, v2, v1, v1}, Ltrycatch/TestSharedCatchCondition;->run(IZZZ)I
    move-result v3
    const/16 v4, 0xc
    if-ne v3, v4, :failed
    sget v3, Ltrycatch/TestSharedCatchCondition;->failures:I
    const/4 v4, 0x0
    if-ne v3, v4, :failed

    invoke-static {v0, v1, v2, v2}, Ltrycatch/TestSharedCatchCondition;->run(IZZZ)I
    move-result v3
    const/16 v4, 0xc
    if-ne v3, v4, :failed
    sget v3, Ltrycatch/TestSharedCatchCondition;->failures:I
    const/4 v4, 0x1
    if-ne v3, v4, :failed

    invoke-static {v0, v2, v1, v2}, Ltrycatch/TestSharedCatchCondition;->run(IZZZ)I
    move-result v3
    const/16 v4, 0xc
    if-ne v3, v4, :failed
    sget v3, Ltrycatch/TestSharedCatchCondition;->failures:I
    const/4 v4, 0x2
    if-ne v3, v4, :failed
    return-void

    :failed
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
