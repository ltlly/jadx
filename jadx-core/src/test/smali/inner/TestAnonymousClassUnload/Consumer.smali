.class public Linner/Consumer;
.super Ljava/lang/Object;
.source "Consumer.java"

.method public static create(Ljava/lang/String;)Ljava/lang/Runnable;
    .registers 2
    .param p0, "value"

    new-instance v0, Linner/Owner$1;
    invoke-direct {v0, p0}, Linner/Owner$1;-><init>(Ljava/lang/String;)V
    return-object v0
.end method
