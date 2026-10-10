.class public Ldst/CodedTop;
.super Ljava/lang/Object;
.source "CodedTop.java"


# direct methods
.method public static top(I)I
    .registers 3

    invoke-static {p0}, Ldst/CodedLeft;->left(I)I

    move-result v0

    invoke-static {p0}, Ldst/CodedRight;->right(I)I

    move-result v1

    add-int/2addr v0, v1

    return v0
.end method
