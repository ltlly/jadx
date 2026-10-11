.class public Ltrycatch/TestCatchAllRethrow;
.super Ljava/lang/Object;

.field public static cleanups:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static checked(Z)I
    .registers 2
    if-eqz p0, :success
    new-instance v0, Ljava/io/IOException;
    invoke-direct {v0}, Ljava/io/IOException;-><init>()V
    throw v0
    :success
    const/4 v0, 0x7
    return v0
.end method

.method public static cleanup()V
    .registers 1
    sget v0, Ltrycatch/TestCatchAllRethrow;->cleanups:I
    add-int/lit8 v0, v0, 0x1
    sput v0, Ltrycatch/TestCatchAllRethrow;->cleanups:I
    return-void
.end method

.method public static asRuntime(Ljava/lang/Throwable;)Ljava/lang/RuntimeException;
    .registers 1
    check-cast p0, Ljava/lang/RuntimeException;
    return-object p0
.end method

.method public static rethrow(Z)I
    .registers 2
    :start
    invoke-static {p0}, Ltrycatch/TestCatchAllRethrow;->checked(Z)I
    move-result v0
    :end
    .catchall {:start .. :end} :failed
    return v0
    :failed
    move-exception v0
    invoke-static {}, Ltrycatch/TestCatchAllRethrow;->cleanup()V
    throw v0
.end method

.method public static swallow(Z)I
    .registers 2
    :start
    invoke-static {p0}, Ltrycatch/TestCatchAllRethrow;->checked(Z)I
    move-result v0
    :end
    .catchall {:start .. :end} :failed
    return v0
    :failed
    move-exception v0
    invoke-static {}, Ltrycatch/TestCatchAllRethrow;->cleanup()V
    const/4 v0, -0x1
    return v0
.end method

.method public static outerCatch(Z)I
    .registers 2
    :start
    invoke-static {p0}, Ltrycatch/TestCatchAllRethrow;->checked(Z)I
    move-result v0
    :end
    .catchall {:start .. :end} :cleanup
    return v0
    :cleanup
    move-exception v0
    :cleanup_start
    invoke-static {}, Ltrycatch/TestCatchAllRethrow;->cleanup()V
    throw v0
    :cleanup_end
    .catch Ljava/io/IOException; {:cleanup_start .. :cleanup_end} :failed
    :failed
    move-exception v0
    const/4 v0, -0x1
    return v0
.end method

.method public static filtered(Z)I
    .registers 3
    :start
    invoke-static {p0}, Ltrycatch/TestCatchAllRethrow;->checked(Z)I
    move-result v0
    :end
    .catchall {:start .. :end} :caught
    return v0
    :caught
    move-exception v0
    instance-of v1, v0, Ljava/lang/RuntimeException;
    if-eqz v1, :swallow
    invoke-static {v0}, Ltrycatch/TestCatchAllRethrow;->asRuntime(Ljava/lang/Throwable;)Ljava/lang/RuntimeException;
    move-result-object v0
    throw v0
    :swallow
    invoke-static {}, Ltrycatch/TestCatchAllRethrow;->cleanup()V
    const/4 v0, -0x1
    return v0
.end method

.method public check()V
    .registers 5
    const/4 v0, 0x0
    const/4 v1, 0x1
    sput v0, Ltrycatch/TestCatchAllRethrow;->cleanups:I
    :success_start
    invoke-static {v0}, Ltrycatch/TestCatchAllRethrow;->rethrow(Z)I
    move-result v2
    :success_end
    .catch Ljava/io/IOException; {:success_start .. :success_end} :unexpected
    const/4 v3, 0x7
    if-ne v2, v3, :failed
    :rethrow_start
    invoke-static {v1}, Ltrycatch/TestCatchAllRethrow;->rethrow(Z)I
    :rethrow_end
    .catch Ljava/io/IOException; {:rethrow_start .. :rethrow_end} :caught
    goto :failed
    :caught
    move-exception v2
    invoke-static {v1}, Ltrycatch/TestCatchAllRethrow;->swallow(Z)I
    move-result v2
    const/4 v3, -0x1
    if-ne v2, v3, :failed
    invoke-static {v1}, Ltrycatch/TestCatchAllRethrow;->outerCatch(Z)I
    move-result v2
    if-ne v2, v3, :failed
    invoke-static {v1}, Ltrycatch/TestCatchAllRethrow;->filtered(Z)I
    move-result v2
    if-ne v2, v3, :failed
    sget v2, Ltrycatch/TestCatchAllRethrow;->cleanups:I
    const/4 v3, 0x4
    if-ne v2, v3, :failed
    return-void
    :unexpected
    move-exception v2
    :failed
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
