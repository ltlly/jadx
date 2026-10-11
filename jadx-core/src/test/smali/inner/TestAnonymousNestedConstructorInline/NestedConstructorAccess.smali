.class public Linner/NestedConstructorAccess;
.super Ljava/lang/Object;

.method public static synthetic access$000()V
    .registers 1

    new-instance v0, Linner/NestedConstructorAnonymous;
    invoke-direct {v0}, Linner/NestedConstructorAnonymous;-><init>()V
    invoke-static {v0}, Linner/NestedConstructorAccess;->accept(Ljava/lang/Runnable;)V
    return-void
.end method

.method public static accept(Ljava/lang/Runnable;)V
    .registers 1

    invoke-interface {p0}, Ljava/lang/Runnable;->run()V
    return-void
.end method
