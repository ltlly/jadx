.class public Ldst/Right;
.super Ljava/lang/Object;
.source "Right.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public readTag()Ljava/lang/String;
    .registers 2

    sget-object v0, Ldst/Shared;->TAG:Ljava/lang/String;

    return-object v0
.end method

.method public readTagTwice()Ljava/lang/String;
    .registers 2

    sget-object v0, Ldst/Shared;->TAG:Ljava/lang/String;

    sget-object v1, Ldst/Shared;->TAG:Ljava/lang/String;

    invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    return-object v0
.end method
