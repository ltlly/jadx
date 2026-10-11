.class public Ltrycatch/TestInstructionCatchThrows;
.super Ljava/lang/Object;
.implements Ljava/util/function/Supplier;

.field public result:Ljava/lang/Object;

.field private final type:Ljava/lang/Class;
    .annotation system Ldalvik/annotation/Signature;
        value = {"Ljava/lang/Class<Ljava/lang/String;>;"}
    .end annotation
.end field

.method public constructor <init>()V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    const-class v0, Ljava/lang/String;
    iput-object v0, p0, Ltrycatch/TestInstructionCatchThrows;->type:Ljava/lang/Class;
    return-void
.end method

.method public get()Ljava/lang/Object;
    .registers 4
    iget-object v0, p0, Ltrycatch/TestInstructionCatchThrows;->type:Ljava/lang/Class;
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
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :handler

    move-object v2, v0
    goto :done

    :handler
    move-exception v0
    const/4 v2, 0x0

    :done
    iput-object v2, p0, Ltrycatch/TestInstructionCatchThrows;->result:Ljava/lang/Object;
    return-object v2
.end method
