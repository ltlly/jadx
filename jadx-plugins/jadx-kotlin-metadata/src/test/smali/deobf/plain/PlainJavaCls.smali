.class public Ldeobf/plain/PlainJavaCls;
.super Ljava/lang/Object;
.source "PlainJavaCls.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public compute(I)I
    .registers 3

    mul-int/lit8 v0, p1, 0x2

    return v0
.end method
