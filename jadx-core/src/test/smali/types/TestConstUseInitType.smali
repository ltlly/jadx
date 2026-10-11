.class public Ltypes/TestConstUseInitType;
.super Ljava/lang/Object;

.field public text:Ljava/lang/String;
.field public number:Ljava/lang/Integer;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

# Keep the shared zero in registers until type inference, as for a Kotlin synthetic constructor.
.method public synthetic constructor <init>(Ljava/lang/String;Ljava/lang/Integer;)V
    .registers 3
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Ltypes/TestConstUseInitType;->text:Ljava/lang/String;
    iput-object p2, p0, Ltypes/TestConstUseInitType;->number:Ljava/lang/Integer;
    return-void
.end method

.method public static test()Ltypes/TestConstUseInitType;
    .registers 2
    new-instance v0, Ltypes/TestConstUseInitType;
    const/4 v1, 0x0
    invoke-direct {v0, v1, v1}, Ltypes/TestConstUseInitType;-><init>(Ljava/lang/String;Ljava/lang/Integer;)V
    return-object v0
.end method

.method public check()V
    .registers 3
    invoke-static {}, Ltypes/TestConstUseInitType;->test()Ltypes/TestConstUseInitType;
    move-result-object v0
    iget-object v1, v0, Ltypes/TestConstUseInitType;->text:Ljava/lang/String;
    if-nez v1, :fail
    iget-object v1, v0, Ltypes/TestConstUseInitType;->number:Ljava/lang/Integer;
    if-nez v1, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
