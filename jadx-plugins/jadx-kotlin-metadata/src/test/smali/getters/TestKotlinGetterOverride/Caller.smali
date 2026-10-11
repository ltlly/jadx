.class public Lgetters/Caller;
.super Ljava/lang/Object;

.method public static call(Lgetters/Contract;)Ljava/lang/String;
    .registers 1
    .param p0, "continuation"
    invoke-interface {p0}, Lgetters/Contract;->getContext()Ljava/lang/String;
    move-result-object p0
    return-object p0
.end method
