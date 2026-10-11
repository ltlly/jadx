.class public final synthetic Linner/PendingThrowsCallback;
.super Ljava/lang/Object;
.implements Ljava/util/concurrent/Callable;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public call()Ljava/lang/Object;
    .registers 2
    invoke-static {}, Linner/PendingThrowsConsumer;->fail()V
    const/4 v0, 0x0
    return-object v0
.end method
