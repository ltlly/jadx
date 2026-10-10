.class public Ldst/Derived;
.super Ldst/GenericBase;
.source "Derived.java"

.annotation system Ldalvik/annotation/Signature;
    value = {
        "Ldst/GenericBase<",
        "Ljava/lang/String;",
        ">;"
    }
.end annotation

.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public map(Ljava/lang/Object;)Ljava/lang/Object;
    .registers 2

    check-cast p1, Ljava/lang/String;

    return-object p1
.end method
