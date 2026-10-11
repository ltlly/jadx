.class public Lgetters/reload/Caller;
.super Ljava/lang/Object;

.method public static call(Lgetters/reload/Base;)Ljava/lang/String;
    .registers 1
    .param p0, "value"
    invoke-virtual {p0}, Lgetters/reload/Base;->a()Ljava/lang/String;
    move-result-object p0
    return-object p0
.end method
