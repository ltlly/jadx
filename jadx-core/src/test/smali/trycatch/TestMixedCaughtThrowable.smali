.class public Ltrycatch/TestMixedCaughtThrowable;
.super Ljava/lang/Object;

.method public static supplied(ZLjava/lang/Throwable;)V
    .registers 3
    .param p0, "replace"
    .param p1, "alternative"

    :try_start
    invoke-static {}, Ltrycatch/TestMixedCaughtThrowable;->fail()V
    :try_end
    .catchall {:try_start .. :try_end} :handler
    return-void

    :handler
    move-exception v0
    if-eqz p0, :throw
    move-object v0, p1

    :throw
    throw v0
.end method

.method public static fresh(Z)V
    .registers 2
    .param p0, "replace"

    :try_start
    invoke-static {}, Ltrycatch/TestMixedCaughtThrowable;->fail()V
    :try_end
    .catchall {:try_start .. :try_end} :handler
    return-void

    :handler
    move-exception v0
    if-eqz p0, :throw
    new-instance v0, Ljava/lang/Throwable;
    invoke-direct {v0}, Ljava/lang/Throwable;-><init>()V

    :throw
    throw v0
.end method

.method private static fail()V
    .registers 1
    new-instance v0, Ljava/lang/IllegalStateException;
    invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V
    throw v0
.end method
