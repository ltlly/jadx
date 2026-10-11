.class public Linner/StaticAnonymousCaller;
.super Ljava/lang/Object;

.method public static create(Ljava/lang/String;)Ljava/lang/Runnable;
    .registers 2
    .param p0, "value"
    new-instance v0, Linner/StaticAnonymousOwner$1;
    invoke-direct {v0, p0}, Linner/StaticAnonymousOwner$1;-><init>(Ljava/lang/String;)V
    return-object v0
.end method

.method public static createWithData(Ljava/lang/String;Linner/StaticAnonymousOwner;)Ljava/lang/Runnable;
    .registers 3
    .param p0, "value"
    .param p1, "data"
    new-instance v0, Linner/StaticAnonymousOwner$3;
    invoke-direct {v0, p0, p1}, Linner/StaticAnonymousOwner$3;-><init>(Ljava/lang/String;Linner/StaticAnonymousOwner;)V
    return-object v0
.end method

.method public static createOwned(Linner/StaticAnonymousOwner;Ljava/lang/String;)Ljava/lang/Runnable;
    .registers 3
    .param p0, "owner"
    .param p1, "value"
    new-instance v0, Linner/StaticAnonymousOwner$2;
    invoke-direct {v0, p0, p1}, Linner/StaticAnonymousOwner$2;-><init>(Linner/StaticAnonymousOwner;Ljava/lang/String;)V
    return-object v0
.end method
