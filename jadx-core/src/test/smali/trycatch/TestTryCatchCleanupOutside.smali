.class public Ltrycatch/TestTryCatchCleanupOutside;
.super Ljava/lang/Object;

.field private static total:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method private static body(Z)V
    .registers 2
    if-eqz p0, :done
    new-instance v0, Ljava/lang/IllegalStateException;
    invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V
    throw v0

    :done
    return-void
.end method

.method private static normalCleanup()V
    .registers 1
    sget v0, Ltrycatch/TestTryCatchCleanupOutside;->total:I
    add-int/lit8 v0, v0, 0x1
    sput v0, Ltrycatch/TestTryCatchCleanupOutside;->total:I
    new-instance v0, Ljava/lang/IllegalStateException;
    invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V
    throw v0
.end method

.method private static catchCleanup()V
    .registers 1
    sget v0, Ltrycatch/TestTryCatchCleanupOutside;->total:I
    add-int/lit8 v0, v0, 0xa
    sput v0, Ltrycatch/TestTryCatchCleanupOutside;->total:I
    return-void
.end method

.method public static run(Z)V
    .registers 2
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    :try_start
    invoke-static {p0}, Ltrycatch/TestTryCatchCleanupOutside;->body(Z)V
    :try_end
    .catchall {:try_start .. :try_end} :handler

    # A failure in normal cleanup must not enter the preceding catch.
    invoke-static {}, Ltrycatch/TestTryCatchCleanupOutside;->normalCleanup()V
    return-void

    :handler
    move-exception v0
    invoke-static {}, Ltrycatch/TestTryCatchCleanupOutside;->catchCleanup()V
    throw v0
.end method

.method private static checkResult(ZI)V
    .registers 3
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const/4 v0, 0x0
    sput v0, Ltrycatch/TestTryCatchCleanupOutside;->total:I

    :try_start
    invoke-static {p0}, Ltrycatch/TestTryCatchCleanupOutside;->run(Z)V
    :try_end
    .catch Ljava/lang/IllegalStateException; {:try_start .. :try_end} :expected
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :expected
    move-exception v0
    sget v0, Ltrycatch/TestTryCatchCleanupOutside;->total:I
    if-eq v0, p1, :done
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :done
    return-void
.end method

.method public check()V
    .registers 2
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const/4 v0, 0x0
    const/4 v1, 0x1
    invoke-static {v0, v1}, Ltrycatch/TestTryCatchCleanupOutside;->checkResult(ZI)V

    const/4 v0, 0x1
    const/16 v1, 0xa
    invoke-static {v0, v1}, Ltrycatch/TestTryCatchCleanupOutside;->checkResult(ZI)V
    return-void
.end method
