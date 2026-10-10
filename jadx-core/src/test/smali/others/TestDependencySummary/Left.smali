.class public Ldst/Left;
.super Ljava/lang/Object;
.source "Left.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public useShared(Ldst/Shared;)Ljava/lang/String;
    .registers 4

    const/4 v0, 0x5

    invoke-interface {p1, v0}, Ldst/Shared;->foo(I)Ljava/lang/String;

    move-result-object v1

    return-object v1
.end method

.method public useLeaf(Ldst/Leaf;)V
    .registers 3

    const/4 v0, 0x1

    invoke-interface {p1, v0}, Ldst/Leaf;->baseOp(I)V

    return-void
.end method
