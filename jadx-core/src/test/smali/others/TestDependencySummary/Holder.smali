.class public Ldst/Holder;
.super Ljava/lang/Object;
.source "Holder.java"


# instance fields
.field private value:Ljava/lang/String;


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method private getValue()Ljava/lang/String;
    .registers 2

    iget-object v0, p0, Ldst/Holder;->value:Ljava/lang/String;

    return-object v0
.end method

.method public accessValue()Ljava/lang/String;
    .registers 2

    invoke-direct {p0}, Ldst/Holder;->getValue()Ljava/lang/String;

    move-result-object v0

    return-object v0
.end method

.method public accessValueTwice()Ljava/lang/String;
    .registers 3

    invoke-direct {p0}, Ldst/Holder;->getValue()Ljava/lang/String;

    move-result-object v0

    invoke-direct {p0}, Ldst/Holder;->getValue()Ljava/lang/String;

    move-result-object v1

    invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    return-object v0
.end method
