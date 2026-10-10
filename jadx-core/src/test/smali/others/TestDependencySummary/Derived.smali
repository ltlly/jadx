.class public Ldst/Derived;
.super Ldst/GenericBase;
.source "Derived.java"


# annotations
.annotation system Ldalvik/annotation/Signature;
    value = {
        "Ldst/GenericBase;",
        "<",
        "Ljava/lang/String;",
        ">;"
    }
.end annotation


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ldst/GenericBase;-><init>()V

    return-void
.end method


# virtual methods
.method public map(Ljava/lang/Object;)Ljava/lang/Object;
    .registers 2

    check-cast v1, Ljava/lang/String;

    return-object v1

    .annotation system Ldalvik/annotation/Signature;
        value = {
            "(",
            "TT;",
            ")TT;"
        }
    .end annotation
.end method
