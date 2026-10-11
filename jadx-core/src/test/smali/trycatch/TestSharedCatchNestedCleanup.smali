.class public Ltrycatch/TestSharedCatchNestedCleanup;
.super Ljava/lang/Object;

.field public static cleanups:I
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

.method public static cleanup(I)V
    .registers 2
    sget v0, Ltrycatch/TestSharedCatchNestedCleanup;->cleanups:I
    add-int/2addr v0, p0
    sput v0, Ltrycatch/TestSharedCatchNestedCleanup;->cleanups:I
    return-void
.end method

.method public static rethrow(Ljava/io/IOException;)V
    .registers 1
    throw p0
.end method

.method public static run(IZZ)I
    .registers 6
    :prepare_start
    invoke-static {p1}, Ltrycatch/TestSharedCatchNestedCleanup;->prepare(Z)I
    move-result v0
    :prepare_end
    .catch Ljava/io/IOException; {:prepare_start .. :prepare_end} :prepare_failed

    move v1, p0
    :finish_start
    invoke-static {v0, p2}, Ltrycatch/TestSharedCatchNestedCleanup;->finish(IZ)I
    move-result v0
    :finish_end
    .catch Ljava/io/IOException; {:finish_start .. :finish_end} :finish_failed
    invoke-static {v1}, Ltrycatch/TestSharedCatchNestedCleanup;->cleanup(I)V
    return v0

    :finish_failed
    move-exception v2
    goto :failed
    :prepare_failed
    move-exception v2
    move v1, p0
    :failed
    :rethrow_start
    invoke-static {v2}, Ltrycatch/TestSharedCatchNestedCleanup;->rethrow(Ljava/io/IOException;)V
    throw v2
    :rethrow_end
    .catchall {:rethrow_start .. :rethrow_end} :cleanup

    :cleanup
    move-exception v2
    invoke-static {v1}, Ltrycatch/TestSharedCatchNestedCleanup;->cleanup(I)V
    throw v2
.end method

.method public static call(IZZ)I
    .registers 4
    :start
    invoke-static {p0, p1, p2}, Ltrycatch/TestSharedCatchNestedCleanup;->run(IZZ)I
    move-result v0
    :end
    .catch Ljava/io/IOException; {:start .. :end} :failed
    return v0
    :failed
    move-exception v0
    invoke-virtual {v0}, Ljava/io/IOException;->getMessage()Ljava/lang/String;
    move-result-object v0
    sput-object v0, Ltrycatch/TestSharedCatchNestedCleanup;->lastError:Ljava/lang/String;
    return p0
.end method

.method public check()V
    .registers 6
    const/16 v0, 0xc
    const/4 v1, 0x0
    const/4 v2, 0x1
    sput v1, Ltrycatch/TestSharedCatchNestedCleanup;->cleanups:I
    invoke-static {v0, v1, v1}, Ltrycatch/TestSharedCatchNestedCleanup;->call(IZZ)I
    move-result v3
    const/4 v4, 0x7
    if-ne v3, v4, :failed
    sget v3, Ltrycatch/TestSharedCatchNestedCleanup;->cleanups:I
    if-ne v3, v0, :failed

    invoke-static {v0, v1, v2}, Ltrycatch/TestSharedCatchNestedCleanup;->call(IZZ)I
    move-result v3
    if-ne v3, v0, :failed
    sget v3, Ltrycatch/TestSharedCatchNestedCleanup;->cleanups:I
    const/16 v4, 0x18
    if-ne v3, v4, :failed
    const-string v3, "finish"
    sget-object v4, Ltrycatch/TestSharedCatchNestedCleanup;->lastError:Ljava/lang/String;
    invoke-virtual {v3, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-eqz v3, :failed

    invoke-static {v0, v2, v1}, Ltrycatch/TestSharedCatchNestedCleanup;->call(IZZ)I
    move-result v3
    if-ne v3, v0, :failed
    sget v3, Ltrycatch/TestSharedCatchNestedCleanup;->cleanups:I
    const/16 v4, 0x24
    if-ne v3, v4, :failed
    const-string v3, "prepare"
    sget-object v4, Ltrycatch/TestSharedCatchNestedCleanup;->lastError:Ljava/lang/String;
    invoke-virtual {v3, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-eqz v3, :failed
    return-void

    :failed
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
