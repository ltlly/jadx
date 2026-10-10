.class public Ldst/MissingRefs;
.super Ljava/lang/Object;
.source "MissingRefs.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public callAll()V
    .registers 2

    invoke-static {}, Lmissing/Missing;->gone()V

    sget v0, Lmissing/Missing;->FIELD:I

    return-void
.end method

.method public readMissingConst()I
    .registers 2

    sget v0, Ldst/Consts;->NO_VALUE:I

    return v0
.end method
