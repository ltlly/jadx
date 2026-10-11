.class public Linner/StaticAnonymousOwner;
.super Ljava/lang/Object;

.field public static lastValue:Ljava/lang/String;
.field public instanceValue:Ljava/lang/String;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static create(Ljava/lang/String;)Ljava/lang/Runnable;
    .registers 1
    .param p0, "value"
    invoke-static {p0}, Linner/StaticAnonymousCaller;->create(Ljava/lang/String;)Ljava/lang/Runnable;
    move-result-object p0
    return-object p0
.end method

.method public createOwned(Ljava/lang/String;)Ljava/lang/Runnable;
    .registers 2
    .param p1, "value"
    invoke-static {p0, p1}, Linner/StaticAnonymousCaller;->createOwned(Linner/StaticAnonymousOwner;Ljava/lang/String;)Ljava/lang/Runnable;
    move-result-object p1
    return-object p1
.end method

.method public static createWithData(Ljava/lang/String;Linner/StaticAnonymousOwner;)Ljava/lang/Runnable;
    .registers 2
    .param p0, "value"
    .param p1, "data"
    invoke-static {p0, p1}, Linner/StaticAnonymousCaller;->createWithData(Ljava/lang/String;Linner/StaticAnonymousOwner;)Ljava/lang/Runnable;
    move-result-object p0
    return-object p0
.end method

.method public check()V
    .registers 3
    const-string v0, "first"
    invoke-static {v0}, Linner/StaticAnonymousOwner;->create(Ljava/lang/String;)Ljava/lang/Runnable;
    move-result-object v1
    invoke-interface {v1}, Ljava/lang/Runnable;->run()V
    sget-object v1, Linner/StaticAnonymousOwner;->lastValue:Ljava/lang/String;
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :failed

    const-string v0, "second"
    invoke-static {v0}, Linner/StaticAnonymousOwner;->create(Ljava/lang/String;)Ljava/lang/Runnable;
    move-result-object v1
    invoke-interface {v1}, Ljava/lang/Runnable;->run()V
    sget-object v1, Linner/StaticAnonymousOwner;->lastValue:Ljava/lang/String;
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :failed

    const/4 v0, 0x0
    invoke-static {v0}, Linner/StaticAnonymousOwner;->create(Ljava/lang/String;)Ljava/lang/Runnable;
    move-result-object v1
    invoke-interface {v1}, Ljava/lang/Runnable;->run()V
    sget-object v1, Linner/StaticAnonymousOwner;->lastValue:Ljava/lang/String;
    if-nez v1, :failed

    const-string v0, "owned"
    invoke-virtual {p0, v0}, Linner/StaticAnonymousOwner;->createOwned(Ljava/lang/String;)Ljava/lang/Runnable;
    move-result-object v1
    invoke-interface {v1}, Ljava/lang/Runnable;->run()V
    iget-object v1, p0, Linner/StaticAnonymousOwner;->instanceValue:Ljava/lang/String;
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :failed

    const-string v0, "data"
    invoke-static {v0, p0}, Linner/StaticAnonymousOwner;->createWithData(Ljava/lang/String;Linner/StaticAnonymousOwner;)Ljava/lang/Runnable;
    move-result-object v1
    invoke-interface {v1}, Ljava/lang/Runnable;->run()V
    iget-object v1, p0, Linner/StaticAnonymousOwner;->instanceValue:Ljava/lang/String;
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :failed
    return-void

    :failed
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
