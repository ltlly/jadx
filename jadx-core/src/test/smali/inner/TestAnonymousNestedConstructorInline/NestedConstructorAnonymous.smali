.class public final synthetic Linner/NestedConstructorAnonymous;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public run()V
    .registers 3

    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-string v1, "nested constructor"
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
