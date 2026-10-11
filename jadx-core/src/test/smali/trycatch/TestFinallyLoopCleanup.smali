.class public Ltrycatch/TestFinallyLoopCleanup;
.super Ljava/lang/Object;

.field private static total:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static run(I)V
    .registers 5
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const/4 v0, 0x0
    sput v0, Ltrycatch/TestFinallyLoopCleanup;->total:I
    :loop
    if-ge v0, p0, :cleanup

    :try_start
    const-string v1, "1"
    invoke-static {v1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v1
    if-lez v1, :cleanup
    add-int/2addr v0, v1
    goto :loop
    :try_end
    .catchall {:try_start .. :try_end} :handler

    :handler
    move-exception v2
    sget v3, Ltrycatch/TestFinallyLoopCleanup;->total:I
    add-int/2addr v3, v0
    sput v3, Ltrycatch/TestFinallyLoopCleanup;->total:I
    throw v2

    :cleanup
    sget v3, Ltrycatch/TestFinallyLoopCleanup;->total:I
    add-int/2addr v3, v0
    sput v3, Ltrycatch/TestFinallyLoopCleanup;->total:I
    return-void
.end method

.method public check()V
    .registers 3
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const/4 v0, 0x2
    invoke-static {v0}, Ltrycatch/TestFinallyLoopCleanup;->run(I)V
    sget v1, Ltrycatch/TestFinallyLoopCleanup;->total:I
    if-eq v0, v1, :done
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :done
    return-void
.end method
