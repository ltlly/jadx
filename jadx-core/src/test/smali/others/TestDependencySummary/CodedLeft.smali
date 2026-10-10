.class public Ldst/CodedLeft;
.super Ljava/lang/Object;
.source "CodedLeft.java"


# direct methods
.method public static left(I)I
    .registers 2

    invoke-static {p0}, Ldst/CodedShared;->compute(I)I

    move-result v0

    return v0
.end method
