.class public Ltrycatch/TestRemovedInstructionCatchThrows;
.super Ljava/lang/Object;

.field private result:Ljava/lang/Object;

.field private final type:Ljava/lang/Class;
    .annotation system Ldalvik/annotation/Signature;
        value = {"Ljava/lang/Class<Ljava/lang/String;>;"}
    .end annotation
.end field

.method public constructor <init>()V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    const-class v0, Ljava/lang/String;
    iput-object v0, p0, Ltrycatch/TestRemovedInstructionCatchThrows;->type:Ljava/lang/Class;
    return-void
.end method

.method public get()Ljava/lang/Object;
    .registers 4
    iget-object v0, p0, Ltrycatch/TestRemovedInstructionCatchThrows;->type:Ljava/lang/Class;
    const/4 v1, 0x0

    :try_start
    invoke-virtual {v0, v1}, Ljava/lang/Class;->getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;
    move-result-object v0
    const/4 v2, 0x1
    invoke-virtual {v0, v2}, Ljava/lang/reflect/AccessibleObject;->setAccessible(Z)V
    invoke-virtual {v0, v1}, Ljava/lang/reflect/Constructor;->newInstance([Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Ljava/lang/String;
    :try_end
    .catch Ljava/lang/RuntimeException; {:try_start .. :try_end} :runtime_handler
    .catchall {:try_start .. :try_end} :rethrow_handler

    move-object v2, v0
    goto :done

    :runtime_handler
    move-exception v0
    const/4 v2, 0x0
    goto :done

    :rethrow_handler
    move-exception v0
    throw v0

    :done
    iput-object v2, p0, Ltrycatch/TestRemovedInstructionCatchThrows;->result:Ljava/lang/Object;
    return-object v2
.end method

.method public check()V
    .registers 3
    invoke-virtual {p0}, Ltrycatch/TestRemovedInstructionCatchThrows;->get()Ljava/lang/Object;
    move-result-object v0
    instance-of v1, v0, Ljava/lang/String;
    if-eqz v1, :fail
    check-cast v0, Ljava/lang/String;
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v0
    if-nez v0, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
