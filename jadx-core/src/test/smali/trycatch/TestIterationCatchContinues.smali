.class public Ltrycatch/TestIterationCatchContinues;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static test([[Ljava/lang/String;)I
    .registers 8
    const/4 v0, 0x0
    const/4 v1, 0x0
    array-length v2, p0
    if-eqz v2, :done

    :group
    if-ge v1, v2, :done
    :try_start_group
    aget-object v3, p0, v1
    array-length v4, v3
    :try_end_group
    .catchall {:try_start_group .. :try_end_group} :next_group
    if-eqz v4, :next_group
    const/4 v5, 0x0

    :category
    if-ge v5, v4, :next_group
    :try_start_category
    aget-object v6, v3, v5
    :try_end_category
    .catchall {:try_start_category .. :try_end_category} :next_group

    :try_start_event
    invoke-static {v6}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v6
    add-int/2addr v0, v6
    :try_end_event
    .catchall {:try_start_event .. :try_end_event} :publish

    # Event errors still publish this category and continue the same group.
    :publish
    :try_start_publish
    const-string v6, "10"
    invoke-static {v6}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v6
    add-int/2addr v0, v6
    :try_end_publish
    .catchall {:try_start_publish .. :try_end_publish} :next_category

    :next_category
    add-int/lit8 v5, v5, 0x1
    goto :category

    :next_group
    add-int/lit8 v1, v1, 0x1
    goto :group

    :done
    return v0
.end method

.method public check()V
    .registers 7
    const-string v0, "bad"
    const-string v1, "2"
    filled-new-array {v0, v1}, [Ljava/lang/String;
    move-result-object v0
    const-string v1, "3"
    filled-new-array {v1}, [Ljava/lang/String;
    move-result-object v1
    filled-new-array {v0, v1}, [[Ljava/lang/String;
    move-result-object v2
    invoke-static {v2}, Ltrycatch/TestIterationCatchContinues;->test([[Ljava/lang/String;)I
    move-result v3
    const/16 v4, 0x23
    if-ne v3, v4, :fail

    # A group-level failure still allows the following group to run.
    const/4 v0, 0x0
    filled-new-array {v0, v1}, [[Ljava/lang/String;
    move-result-object v2
    invoke-static {v2}, Ltrycatch/TestIterationCatchContinues;->test([[Ljava/lang/String;)I
    move-result v3
    const/16 v4, 0xd
    if-ne v3, v4, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
