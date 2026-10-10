.class public Ldst/CodedRight;
.super Ljava/lang/Object;
.source "CodedRight.java"


# direct methods
.method public static right(I)I
    .registers 2

    invoke-static {p0}, Ldst/CodedShared;->compute(I)I

    move-result v0

    return v0
.end method
