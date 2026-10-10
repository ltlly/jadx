.class public Ldst/Pong;
.super Ljava/lang/Object;
.source "Pong.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static pong()V
    .registers 1

    invoke-static {}, Ldst/Ping;->ping()V

    return-void
.end method

.method public static pongValue()Ljava/lang/String;
    .registers 2

    invoke-static {}, Ldst/Ping;->pingValue()Ljava/lang/String;

    move-result-object v0

    return-object v0
.end method
