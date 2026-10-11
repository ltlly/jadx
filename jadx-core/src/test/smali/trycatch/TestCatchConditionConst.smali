.class public Ltrycatch/TestCatchConditionConst;
.super Ljava/lang/Object;

.field public static final MASK:J = 0x200L
.field private value:J
.field private reads:I
.field private closes:I
.field private failRead:Z

.method public constructor <init>()V
    .registers 3
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    const-wide/16 v0, 0x200
    iput-wide v0, p0, Ltrycatch/TestCatchConditionConst;->value:J
    return-void
.end method

.method private read()J
    .registers 3
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->reads:I
    add-int/lit8 v0, v0, 0x1
    iput v0, p0, Ltrycatch/TestCatchConditionConst;->reads:I
    iget-boolean v0, p0, Ltrycatch/TestCatchConditionConst;->failRead:Z
    if-nez v0, :fail
    iget-wide v0, p0, Ltrycatch/TestCatchConditionConst;->value:J
    return-wide v0
    :fail
    new-instance v0, Ljava/lang/NullPointerException;
    invoke-direct {v0}, Ljava/lang/NullPointerException;-><init>()V
    throw v0
.end method

.method private close()V
    .registers 2
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->closes:I
    add-int/lit8 v0, v0, 0x1
    iput v0, p0, Ltrycatch/TestCatchConditionConst;->closes:I
    return-void
.end method

.method public test(I)Z
    .registers 8
    const/4 v4, 0x1
    const/4 v5, 0x0
    :try_body
    const/4 v0, -0x1
    if-le p1, v0, :condition_false
    invoke-direct {p0}, Ltrycatch/TestCatchConditionConst;->read()J
    move-result-wide v0
    :try_body_end
    .catchall {:try_body .. :try_body_end} :close_on_error

    const-wide/16 v2, 0x200
    and-long/2addr v0, v2
    const-wide/16 v2, 0x0
    cmp-long v0, v0, v2
    if-eqz v0, :condition_false
    goto :condition_done
    :condition_false
    move v4, v5
    :condition_done

    :try_normal_close
    invoke-direct {p0}, Ltrycatch/TestCatchConditionConst;->close()V
    :try_normal_close_end
    .catch Ljava/lang/NullPointerException; {:try_normal_close .. :try_normal_close_end} :return_false
    return v4

    :close_on_error
    move-exception v0
    :try_error_close
    invoke-direct {p0}, Ltrycatch/TestCatchConditionConst;->close()V
    :try_error_close_end
    .catchall {:try_error_close .. :try_error_close_end} :suppress_close_error
    goto :rethrow

    :suppress_close_error
    move-exception v1
    :try_suppress
    invoke-virtual {v0, v1}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V
    :try_suppress_end
    .catch Ljava/lang/NullPointerException; {:try_suppress .. :try_suppress_end} :return_false

    :rethrow
    :try_rethrow
    throw v0
    :try_rethrow_end
    .catch Ljava/lang/NullPointerException; {:try_rethrow .. :try_rethrow_end} :return_false

    :return_false
    const/4 v0, 0x0
    return v0
.end method

.method public check()V
    .registers 5
    const/4 v0, -0x1
    invoke-virtual {p0, v0}, Ltrycatch/TestCatchConditionConst;->test(I)Z
    move-result v0
    if-nez v0, :fail
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->reads:I
    if-nez v0, :fail
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->closes:I
    const/4 v1, 0x1
    if-ne v0, v1, :fail

    const/4 v0, 0x0
    invoke-virtual {p0, v0}, Ltrycatch/TestCatchConditionConst;->test(I)Z
    move-result v0
    if-eqz v0, :fail
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->reads:I
    if-ne v0, v1, :fail
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->closes:I
    const/4 v2, 0x2
    if-ne v0, v2, :fail

    const-wide/16 v2, 0x0
    iput-wide v2, p0, Ltrycatch/TestCatchConditionConst;->value:J
    const/4 v0, 0x0
    invoke-virtual {p0, v0}, Ltrycatch/TestCatchConditionConst;->test(I)Z
    move-result v0
    if-nez v0, :fail
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->reads:I
    const/4 v2, 0x2
    if-ne v0, v2, :fail
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->closes:I
    const/4 v2, 0x3
    if-ne v0, v2, :fail

    iput-boolean v1, p0, Ltrycatch/TestCatchConditionConst;->failRead:Z
    const/4 v0, 0x0
    invoke-virtual {p0, v0}, Ltrycatch/TestCatchConditionConst;->test(I)Z
    move-result v0
    if-nez v0, :fail
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->reads:I
    if-ne v0, v2, :fail
    iget v0, p0, Ltrycatch/TestCatchConditionConst;->closes:I
    const/4 v1, 0x4
    if-ne v0, v1, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
