.class public Linner/StaticAnonymousOwner$1;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Linner/StaticAnonymousOwner;->create(Ljava/lang/String;)Ljava/lang/Runnable;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x1
    name = null
.end annotation

.field private final synthetic value:Ljava/lang/String;

.method public constructor <init>(Ljava/lang/String;)V
    .registers 2
    .param p1, "value"
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Linner/StaticAnonymousOwner$1;->value:Ljava/lang/String;
    return-void
.end method

.method public run()V
    .registers 2
    iget-object v0, p0, Linner/StaticAnonymousOwner$1;->value:Ljava/lang/String;
    sput-object v0, Linner/StaticAnonymousOwner;->lastValue:Ljava/lang/String;
    return-void
.end method
