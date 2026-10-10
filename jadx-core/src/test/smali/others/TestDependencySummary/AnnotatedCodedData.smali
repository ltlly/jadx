.class public Ldst/AnnotatedData;
.super Ljava/lang/Object;
.source "AnnotatedData.java"

.annotation runtime Ldst/Marker;
    ref = Ldst/Consts;->KNOWN:I
.end annotation

.field public static flag:Z

.method public read()Ljava/lang/String;
    .registers 2

    const-string v0, "value"

    return-object v0
.end method
