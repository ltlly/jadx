.class public Ldst/AnnotatedData;
.super Ljava/lang/Object;
.source "AnnotatedData.java"

.annotation runtime Ldst/Marker;
    ref = Ldst/Consts;->KNOWN:I
    names = { "alpha", "beta" }
    nested = .subannotation Ldst/Nested;
        x = 0x5
    .end subannotation
.end annotation


# static fields
.field public static flag:Z
    .annotation runtime Ldst/FlagAnno;
        on = true
    .end annotation
.end field


# direct methods
.method public abstract read()Ljava/lang/String;
    .annotation runtime Ldst/UseAnno;
        mode = Ldst/Consts;->KNOWN:I
    .end annotation
.end method
