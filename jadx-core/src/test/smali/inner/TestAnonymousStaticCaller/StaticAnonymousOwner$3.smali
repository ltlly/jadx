.class public Linner/StaticAnonymousOwner$3;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Linner/StaticAnonymousOwner;->createWithData(Ljava/lang/String;Linner/StaticAnonymousOwner;)Ljava/lang/Runnable;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x1
    name = null
.end annotation

.field private final synthetic value:Ljava/lang/String;
.field private final synthetic data:Linner/StaticAnonymousOwner;

.method public constructor <init>(Ljava/lang/String;Linner/StaticAnonymousOwner;)V
    .registers 3
    .param p1, "value"
    .param p2, "data"
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Linner/StaticAnonymousOwner$3;->value:Ljava/lang/String;
    iput-object p2, p0, Linner/StaticAnonymousOwner$3;->data:Linner/StaticAnonymousOwner;
    return-void
.end method

.method public run()V
    .registers 3
    iget-object v0, p0, Linner/StaticAnonymousOwner$3;->data:Linner/StaticAnonymousOwner;
    iget-object v1, p0, Linner/StaticAnonymousOwner$3;->value:Ljava/lang/String;
    iput-object v1, v0, Linner/StaticAnonymousOwner;->instanceValue:Ljava/lang/String;
    return-void
.end method
