.class public Ldst/Ping;
.super Ljava/lang/Object;
.source "Ping.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static ping()V
    .registers 1

    sget-object v0, Ldst/Ping;->own:Ljava/lang/Object;

    invoke-static {}, Ldst/Pong;->pong()V

    return-void
.end method

.method public static pingValue()Ljava/lang/String;
    .registers 2

    invoke-static {}, Ldst/Pong;->pongValue()Ljava/lang/String;

    move-result-object v0

    return-object v0
.end method

.field static own:Ljava/lang/Object;
