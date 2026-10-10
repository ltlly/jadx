.class public Ldst/SigUser;
.super Ljava/lang/Object;
.source "SigUser.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public call(Ldst/Sig;Ljava/lang/String;)V
    .registers 3

    invoke-interface {p1, p2}, Ldst/Sig;->checked(Ljava/lang/String;)V

    invoke-interface {p1}, Ldst/Sig;->other()V

    return-void
.end method
