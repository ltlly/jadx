.class public Ltrycatch/TestCatchBoundaryMove;
.super Ljava/lang/Object;
.implements Ljava/util/Iterator;

.field private index:I
.field private final throwOnNext:Z

.method public constructor <init>()V
    .registers 2
    const/4 v0, 0x0
    invoke-direct {p0, v0}, Ltrycatch/TestCatchBoundaryMove;-><init>(Z)V
    return-void
.end method

.method public constructor <init>(Z)V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-boolean p1, p0, Ltrycatch/TestCatchBoundaryMove;->throwOnNext:Z
    return-void
.end method

.method public hasNext()Z
    .registers 3
    iget v0, p0, Ltrycatch/TestCatchBoundaryMove;->index:I
    const/4 v1, 0x2
    if-ge v0, v1, :done
    const/4 v0, 0x1
    return v0
    :done
    const/4 v0, 0x0
    return v0
.end method

.method public next()Ljava/lang/Object;
    .registers 4
    iget v0, p0, Ltrycatch/TestCatchBoundaryMove;->index:I
    const/4 v1, 0x2
    if-ge v0, v1, :exhausted
    add-int/lit8 v1, v0, 0x1
    iput v1, p0, Ltrycatch/TestCatchBoundaryMove;->index:I
    if-nez v0, :second
    iget-boolean v1, p0, Ltrycatch/TestCatchBoundaryMove;->throwOnNext:Z
    if-nez v1, :fail
    const-string v0, "bad"
    return-object v0
    :second
    const-string v0, "2"
    return-object v0
    :fail
    new-instance v0, Ljava/lang/IllegalStateException;
    invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V
    throw v0
    :exhausted
    new-instance v0, Ljava/util/NoSuchElementException;
    invoke-direct {v0}, Ljava/util/NoSuchElementException;-><init>()V
    throw v0
.end method

.method public static consume(Ljava/util/Iterator;)I
    .registers 3
    const/4 v0, 0x0

    :try_start_iteration
    :next_record
    invoke-interface {p0}, Ljava/util/Iterator;->hasNext()Z
    move-result v1
    if-eqz v1, :done
    invoke-interface {p0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
    move-result-object v1
    check-cast v1, Ljava/lang/String;
    :try_end_iteration
    .catchall {:try_start_iteration .. :try_end_iteration} :iteration_failed

    :try_start_record
    invoke-static {v1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v1
    add-int/2addr v0, v1
    :try_end_record
    .catchall {:try_start_record .. :try_end_record} :next_record
    goto :next_record

    :done
    return v0

    :iteration_failed
    new-instance v1, Ljava/lang/IllegalArgumentException;
    invoke-direct {v1}, Ljava/lang/IllegalArgumentException;-><init>()V
    throw v1
.end method

.method public check()V
    .registers 3
    new-instance v0, Ltrycatch/TestCatchBoundaryMove;
    const/4 v1, 0x1
    invoke-direct {v0, v1}, Ltrycatch/TestCatchBoundaryMove;-><init>(Z)V

    :try_expect_iteration_failure
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryMove;->consume(Ljava/util/Iterator;)I
    :end_expect_iteration_failure
    .catch Ljava/lang/IllegalArgumentException; {:try_expect_iteration_failure .. :end_expect_iteration_failure} :next_check
    goto :fail

    :next_check
    new-instance v0, Ltrycatch/TestCatchBoundaryMove;
    const/4 v1, 0x0
    invoke-direct {v0, v1}, Ltrycatch/TestCatchBoundaryMove;-><init>(Z)V
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryMove;->consume(Ljava/util/Iterator;)I
    move-result v0
    const/4 v1, 0x2
    if-ne v0, v1, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
