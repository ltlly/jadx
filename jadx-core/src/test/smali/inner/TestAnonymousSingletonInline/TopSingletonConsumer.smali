.class public Linner/TopSingletonConsumer;
.super Ljava/lang/Object;

.method public static create()Ljava/lang/Runnable;
    .registers 1

    invoke-static {}, Linner/TopSingletonAccess;->access$000()Ljava/lang/Runnable;
    move-result-object v0
    return-object v0
.end method
