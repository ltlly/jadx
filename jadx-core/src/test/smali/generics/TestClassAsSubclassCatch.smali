.class public Lgenerics/TestClassAsSubclassCatch;
.super Ljava/lang/Object;

.method public static create(Ljava/lang/String;)Ljava/lang/Runnable;
    .locals 2
    :try_start
    invoke-static {p0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v0
    const-class v1, Ljava/lang/Runnable;
    invoke-virtual {v0, v1}, Ljava/lang/Class;->asSubclass(Ljava/lang/Class;)Ljava/lang/Class;
    move-result-object v0
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :catch
    invoke-static {v0}, Lgenerics/TestClassAsSubclassCatch;->create(Ljava/lang/Class;)Ljava/lang/Runnable;
    move-result-object v0
    return-object v0
    :catch
    move-exception v0
    new-instance v1, Ljava/lang/IllegalArgumentException;
    invoke-direct {v1, v0}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/Throwable;)V
    throw v1
.end method

.method private static create(Ljava/lang/Class;)Ljava/lang/Runnable;
    .locals 2
    .annotation system Ldalvik/annotation/Signature;
        value = {"(Ljava/lang/Class<+Ljava/lang/Runnable;>;)Ljava/lang/Runnable;"}
    .end annotation
    :try_start
    invoke-virtual {p0}, Ljava/lang/Class;->newInstance()Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Ljava/lang/Runnable;
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :catch
    return-object v0
    :catch
    move-exception v0
    new-instance v1, Ljava/lang/IllegalArgumentException;
    invoke-direct {v1, v0}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/Throwable;)V
    throw v1
.end method

.method public check()V
    .locals 1
    .annotation system Ldalvik/annotation/Throws;
        value = {Ljava/lang/Exception;}
    .end annotation
    const-class v0, Lgenerics/TestClassAsSubclassCatch;
    invoke-static {v0}, Ljadx/tests/integration/generics/TestClassAsSubclassCatch;->check(Ljava/lang/Class;)V
    return-void
.end method
