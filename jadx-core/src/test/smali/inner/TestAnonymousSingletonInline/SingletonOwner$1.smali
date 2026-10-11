.class public final Linner/SingletonOwner$1;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

.annotation system Ldalvik/annotation/EnclosingClass;
    value = Linner/SingletonOwner;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x19
    name = "1"
.end annotation

.field public static final INSTANCE:Linner/SingletonOwner$1;

.method static constructor <clinit>()V
    .registers 1

    new-instance v0, Linner/SingletonOwner$1;
    invoke-direct {v0}, Linner/SingletonOwner$1;-><init>()V
    sput-object v0, Linner/SingletonOwner$1;->INSTANCE:Linner/SingletonOwner$1;
    return-void
.end method

.method private constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public run()V
    .registers 3

    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-string v1, "inner singleton"
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
