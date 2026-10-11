.class public Ltrycatch/TestEmptyCatchContinuesLoop;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static test(Ljava/util/List;)I
    .registers 4
    const/4 v0, 0x0

    :try_start_iteration
    invoke-interface {p0}, Ljava/util/List;->iterator()Ljava/util/Iterator;
    move-result-object v1

    :next_record
    invoke-interface {v1}, Ljava/util/Iterator;->hasNext()Z
    move-result v2
    if-eqz v2, :done
    invoke-interface {v1}, Ljava/util/Iterator;->next()Ljava/lang/Object;
    move-result-object v2
    check-cast v2, Ljava/lang/String;
    :try_end_iteration
    .catchall {:try_start_iteration .. :try_end_iteration} :done

    :try_start_record
    invoke-static {v2}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v2
    add-int/2addr v0, v2
    :try_end_record
    .catchall {:try_start_record .. :try_end_record} :next_record
    goto :next_record

    :done
    return v0
.end method

.method public check()V
    .registers 5
    const-string v0, "bad"
    const-string v1, "2"
    filled-new-array {v0, v1}, [Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Ljava/util/Arrays;->asList([Ljava/lang/Object;)Ljava/util/List;
    move-result-object v0
    invoke-static {v0}, Ltrycatch/TestEmptyCatchContinuesLoop;->test(Ljava/util/List;)I
    move-result v0
    const/4 v1, 0x2
    if-ne v0, v1, :fail

    const-string v0, "1"
    const-string v1, "bad"
    const-string v2, "3"
    filled-new-array {v0, v1, v2}, [Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Ljava/util/Arrays;->asList([Ljava/lang/Object;)Ljava/util/List;
    move-result-object v0
    invoke-static {v0}, Ltrycatch/TestEmptyCatchContinuesLoop;->test(Ljava/util/List;)I
    move-result v0
    const/4 v1, 0x4
    if-ne v0, v1, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
