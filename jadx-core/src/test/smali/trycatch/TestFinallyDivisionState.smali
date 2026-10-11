.class public Ltrycatch/TestFinallyDivisionState;
.super Ljava/lang/Object;

.field private result:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public runInt(Ljava/lang/String;)V
    .registers 6
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const/4 v0, 0x2
    .local v0, "success":I
    const/4 v1, 0x2

    :try_start
    invoke-static {p1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    :try_end
    .catchall {:try_start .. :try_end} :handler

    # A literal nonzero divisor cannot throw, and this update precedes finally.
    div-int/lit8 v0, v0, 0x2
    if-ne v0, v1, :done
    iget v2, p0, Ltrycatch/TestFinallyDivisionState;->result:I
    add-int/lit8 v2, v2, -0x2
    iput v2, p0, Ltrycatch/TestFinallyDivisionState;->result:I

    :done
    return-void

    :handler
    move-exception v3
    if-ne v0, v1, :rethrow
    iget v2, p0, Ltrycatch/TestFinallyDivisionState;->result:I
    add-int/lit8 v2, v2, -0x2
    iput v2, p0, Ltrycatch/TestFinallyDivisionState;->result:I

    :rethrow
    throw v3
.end method

.method public runFloat(Ljava/lang/String;)V
    .registers 7
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const/high16 v0, 0x40000000
    .local v0, "success":F
    const/high16 v1, 0x40000000
    const/4 v4, 0x0

    :try_start
    invoke-static {p1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    :try_end
    .catchall {:try_start .. :try_end} :handler

    # Floating division cannot throw ArithmeticException, even for a zero divisor.
    div-float/2addr v0, v4
    cmpl-float v2, v0, v1
    if-nez v2, :done
    iget v3, p0, Ltrycatch/TestFinallyDivisionState;->result:I
    add-int/lit8 v3, v3, -0x2
    iput v3, p0, Ltrycatch/TestFinallyDivisionState;->result:I

    :done
    return-void

    :handler
    move-exception v4
    cmpl-float v2, v0, v1
    if-nez v2, :rethrow
    iget v3, p0, Ltrycatch/TestFinallyDivisionState;->result:I
    add-int/lit8 v3, v3, -0x2
    iput v3, p0, Ltrycatch/TestFinallyDivisionState;->result:I

    :rethrow
    throw v4
.end method

.method private assertResult(I)V
    .registers 3
    iget v0, p0, Ltrycatch/TestFinallyDivisionState;->result:I
    if-ne v0, p1, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method

.method public check()V
    .registers 3
    .annotation system Ldalvik/annotation/Throws;
        value = { Ljava/lang/Throwable; }
    .end annotation

    const-string v0, "1"
    invoke-virtual {p0, v0}, Ltrycatch/TestFinallyDivisionState;->runInt(Ljava/lang/String;)V
    const/4 v1, 0x0
    invoke-direct {p0, v1}, Ltrycatch/TestFinallyDivisionState;->assertResult(I)V

    const-string v0, "bad"
    :try_start_int
    invoke-virtual {p0, v0}, Ltrycatch/TestFinallyDivisionState;->runInt(Ljava/lang/String;)V
    :try_end_int
    .catch Ljava/lang/NumberFormatException; {:try_start_int .. :try_end_int} :expected_int
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :expected_int
    move-exception v0
    const/4 v1, -0x2
    invoke-direct {p0, v1}, Ltrycatch/TestFinallyDivisionState;->assertResult(I)V

    const/4 v0, 0x0
    iput v0, p0, Ltrycatch/TestFinallyDivisionState;->result:I
    const-string v0, "1"
    invoke-virtual {p0, v0}, Ltrycatch/TestFinallyDivisionState;->runFloat(Ljava/lang/String;)V
    const/4 v1, 0x0
    invoke-direct {p0, v1}, Ltrycatch/TestFinallyDivisionState;->assertResult(I)V

    const-string v0, "bad"
    :try_start_float
    invoke-virtual {p0, v0}, Ltrycatch/TestFinallyDivisionState;->runFloat(Ljava/lang/String;)V
    :try_end_float
    .catch Ljava/lang/NumberFormatException; {:try_start_float .. :try_end_float} :expected_float
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :expected_float
    move-exception v0
    const/4 v1, -0x2
    invoke-direct {p0, v1}, Ltrycatch/TestFinallyDivisionState;->assertResult(I)V
    return-void
.end method
