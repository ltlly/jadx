.class public Ltrycatch/TestFinallyArithmeticState;
.super Ljava/lang/Object;

.field private result:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public run(Ljava/lang/String;)V
    .registers 5
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const/4 v0, 0x0
    .local v0, "success":I

    :try_start
    invoke-static {p1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    :try_end
    .catchall {:try_start .. :try_end} :handler

    # This nonthrowing state update must precede the extracted finally.
    add-int/lit8 v0, v0, 0x1
    if-nez v0, :done
    iget v1, p0, Ltrycatch/TestFinallyArithmeticState;->result:I
    add-int/lit8 v1, v1, -0x2
    iput v1, p0, Ltrycatch/TestFinallyArithmeticState;->result:I

    :done
    return-void

    :handler
    move-exception v2
    if-nez v0, :rethrow
    iget v1, p0, Ltrycatch/TestFinallyArithmeticState;->result:I
    add-int/lit8 v1, v1, -0x2
    iput v1, p0, Ltrycatch/TestFinallyArithmeticState;->result:I

    :rethrow
    throw v2
.end method

.method public check()V
    .registers 3
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const-string v0, "1"
    invoke-virtual {p0, v0}, Ltrycatch/TestFinallyArithmeticState;->run(Ljava/lang/String;)V
    iget v0, p0, Ltrycatch/TestFinallyArithmeticState;->result:I
    if-eqz v0, :exception_case
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :exception_case
    const-string v0, "bad"
    :try_start
    invoke-virtual {p0, v0}, Ltrycatch/TestFinallyArithmeticState;->run(Ljava/lang/String;)V
    :try_end
    .catch Ljava/lang/NumberFormatException; {:try_start .. :try_end} :expected
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :expected
    move-exception v0
    iget v0, p0, Ltrycatch/TestFinallyArithmeticState;->result:I
    const/4 v1, -0x2
    if-eq v0, v1, :done
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :done
    return-void
.end method
