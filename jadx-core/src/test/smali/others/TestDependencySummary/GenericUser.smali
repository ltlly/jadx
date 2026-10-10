.class public Ldst/GenericUser;
.super Ljava/lang/Object;
.source "GenericUser.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public use(Ldst/Derived;)Ljava/lang/String;
    .registers 3

    invoke-virtual {p1}, Ldst/Derived;->map(Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    check-cast v0, Ljava/lang/String;

    return-object v0
.end method
