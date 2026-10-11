.class public Lgetters/reload/Child;
.super Lgetters/reload/Base;

.method public constructor <init>()V
    .registers 2
    const-string v0, "base"
    invoke-direct {p0, v0}, Lgetters/reload/Base;-><init>(Ljava/lang/String;)V
    return-void
.end method

.method public a()Ljava/lang/String;
    .registers 2
    const-string v0, "child"
    return-object v0
.end method

# Different return type: this declaration does not override Base.b():int.
.method public b()Ljava/lang/String;
    .registers 2
    const-string v0, "child"
    return-object v0
.end method

.method public c()Ljava/lang/String;
    .registers 2
    const-string v0, "covariant"
    return-object v0
.end method
