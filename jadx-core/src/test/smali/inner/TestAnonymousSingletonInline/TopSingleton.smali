.class public final synthetic Linner/TopSingleton;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

.field public static final INSTANCE:Linner/TopSingleton;

.method static constructor <clinit>()V
    .registers 1

    new-instance v0, Linner/TopSingleton;
    invoke-direct {v0}, Linner/TopSingleton;-><init>()V
    sput-object v0, Linner/TopSingleton;->INSTANCE:Linner/TopSingleton;
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
    const-string v1, "top singleton"
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    return-void
.end method
