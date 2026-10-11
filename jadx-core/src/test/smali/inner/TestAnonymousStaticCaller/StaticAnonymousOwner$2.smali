.class public Linner/StaticAnonymousOwner$2;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Linner/StaticAnonymousOwner;->createOwned(Ljava/lang/String;)Ljava/lang/Runnable;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x1
    name = null
.end annotation

.field private final synthetic this$0:Linner/StaticAnonymousOwner;
.field private final synthetic value:Ljava/lang/String;

.method public constructor <init>(Linner/StaticAnonymousOwner;Ljava/lang/String;)V
    .registers 3
    .param p1, "owner"
    .param p2, "value"
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Linner/StaticAnonymousOwner$2;->this$0:Linner/StaticAnonymousOwner;
    iput-object p2, p0, Linner/StaticAnonymousOwner$2;->value:Ljava/lang/String;
    return-void
.end method

.method public run()V
    .registers 3
    iget-object v0, p0, Linner/StaticAnonymousOwner$2;->this$0:Linner/StaticAnonymousOwner;
    iget-object v1, p0, Linner/StaticAnonymousOwner$2;->value:Ljava/lang/String;
    iput-object v1, v0, Linner/StaticAnonymousOwner;->instanceValue:Ljava/lang/String;
    return-void
.end method
