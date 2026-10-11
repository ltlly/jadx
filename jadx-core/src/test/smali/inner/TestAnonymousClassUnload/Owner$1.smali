.class public Linner/Owner$1;
.super Ljava/lang/Object;
.source "Owner.java"
.implements Ljava/lang/Runnable;

.annotation system Ldalvik/annotation/EnclosingClass;
    value = Linner/Owner;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x9
    name = "1"
.end annotation

.field private final synthetic value:Ljava/lang/String;

.method public constructor <init>(Ljava/lang/String;)V
    .registers 2

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Linner/Owner$1;->value:Ljava/lang/String;
    return-void
.end method

.method public run()V
    .registers 3

    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    iget-object v1, p0, Linner/Owner$1;->value:Ljava/lang/String;
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
