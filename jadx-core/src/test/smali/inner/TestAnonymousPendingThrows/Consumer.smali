.class public Linner/PendingThrowsConsumer;
.super Ljava/lang/Object;

.method public static create()Ljava/util/concurrent/Callable;
    .registers 1
    new-instance v0, Linner/PendingThrowsCallback;
    invoke-direct {v0}, Linner/PendingThrowsCallback;-><init>()V
    return-object v0
.end method

.method public static fail()V
    .registers 0
    invoke-static {}, Lothers/TestMethodThrowsLateSource;->fail()V
    return-void
.end method
