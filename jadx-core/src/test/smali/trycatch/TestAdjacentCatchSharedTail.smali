.class public Ltrycatch/TestAdjacentCatchSharedTail;
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

.method public static run(IZZ)I
    .registers 7
    :prepare_start
    invoke-static {p1}, Ltrycatch/TestAdjacentCatchSharedTail;->prepare(Z)I
    move-result v0
    :prepare_end
    .catch Ljava/io/IOException; {:prepare_start .. :prepare_end} :prepare_failed

    move v1, p0
    :finish_start
    invoke-static {v0, p2}, Ltrycatch/TestAdjacentCatchSharedTail;->finish(IZ)I
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
    invoke-virtual {v2}, Ljava/io/IOException;->getMessage()Ljava/lang/String;
    move-result-object v3
    sput-object v3, Ltrycatch/TestAdjacentCatchSharedTail;->lastError:Ljava/lang/String;
    sget v3, Ltrycatch/TestAdjacentCatchSharedTail;->failures:I
    add-int/lit8 v3, v3, 0x1
    sput v3, Ltrycatch/TestAdjacentCatchSharedTail;->failures:I
    return v1
.end method

.method public check()V
    .registers 5
    const/16 v0, 0xc
    const/4 v1, 0x0
    sput v1, Ltrycatch/TestAdjacentCatchSharedTail;->failures:I
    invoke-static {v0, v1, v1}, Ltrycatch/TestAdjacentCatchSharedTail;->run(IZZ)I
    move-result v3
    const/4 v2, 0x7
    if-ne v3, v2, :failed
    sget v3, Ltrycatch/TestAdjacentCatchSharedTail;->failures:I
    if-nez v3, :failed

    const/4 v2, 0x1
    invoke-static {v0, v1, v2}, Ltrycatch/TestAdjacentCatchSharedTail;->run(IZZ)I
    move-result v3
    if-ne v3, v0, :failed
    sget v3, Ltrycatch/TestAdjacentCatchSharedTail;->failures:I
    if-ne v3, v2, :failed
    const-string v3, "finish"
    sget-object v4, Ltrycatch/TestAdjacentCatchSharedTail;->lastError:Ljava/lang/String;
    invoke-virtual {v3, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-eqz v3, :failed

    invoke-static {v0, v2, v1}, Ltrycatch/TestAdjacentCatchSharedTail;->run(IZZ)I
    move-result v3
    if-ne v3, v0, :failed
    sget v3, Ltrycatch/TestAdjacentCatchSharedTail;->failures:I
    const/4 v2, 0x2
    if-ne v3, v2, :failed
    const-string v3, "prepare"
    sget-object v4, Ltrycatch/TestAdjacentCatchSharedTail;->lastError:Ljava/lang/String;
    invoke-virtual {v3, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-eqz v3, :failed
    return-void

    :failed
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
