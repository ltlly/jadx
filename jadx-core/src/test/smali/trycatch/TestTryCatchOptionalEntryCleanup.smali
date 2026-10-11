.class public Ltrycatch/TestTryCatchOptionalEntryCleanup;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static test(ZZLjava/lang/String;Ljava/lang/String;)I
    .registers 5
    if-eqz p0, :second

    :try_start_first
    const-string v0, "1"
    invoke-static {v0}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    :try_end_first
    .catch Ljava/lang/NumberFormatException; {:try_start_first .. :try_end_first} :catch

    :second
    if-eqz p1, :cleanup
    :try_start_second
    invoke-static {p2}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    :try_end_second
    .catch Ljava/lang/NumberFormatException; {:try_start_second .. :try_end_second} :catch

    :cleanup
    # This call is outside both optional protected ranges.
    invoke-static {p3}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v0
    return v0

    :catch
    move-exception v0
    const/4 v0, -0x1
    return v0
.end method

.method private static checkNormal(ZZ)V
    .registers 5
    const-string v0, "7"
    invoke-static {p0, p1, v0, v0}, Ltrycatch/TestTryCatchOptionalEntryCleanup;->test(ZZLjava/lang/String;Ljava/lang/String;)I
    move-result v0
    const/4 v1, 0x7
    if-eq v0, v1, :done
    new-instance v2, Ljava/lang/AssertionError;
    invoke-direct {v2}, Ljava/lang/AssertionError;-><init>()V
    throw v2

    :done
    return-void
.end method

.method public check()V
    .registers 5
    const/4 v0, 0x0
    const/4 v1, 0x1
    invoke-static {v0, v0}, Ltrycatch/TestTryCatchOptionalEntryCleanup;->checkNormal(ZZ)V
    invoke-static {v0, v1}, Ltrycatch/TestTryCatchOptionalEntryCleanup;->checkNormal(ZZ)V
    invoke-static {v1, v0}, Ltrycatch/TestTryCatchOptionalEntryCleanup;->checkNormal(ZZ)V
    invoke-static {v1, v1}, Ltrycatch/TestTryCatchOptionalEntryCleanup;->checkNormal(ZZ)V

    # The second protected call still catches when the first one is bypassed.
    const-string v2, "bad"
    const-string v3, "7"
    invoke-static {v0, v1, v2, v3}, Ltrycatch/TestTryCatchOptionalEntryCleanup;->test(ZZLjava/lang/String;Ljava/lang/String;)I
    move-result v3
    const/4 v4, -0x1
    if-ne v3, v4, :fail

    # Cleanup must throw even when both protected calls are bypassed.
    :try_start
    invoke-static {v0, v0, v2, v2}, Ltrycatch/TestTryCatchOptionalEntryCleanup;->test(ZZLjava/lang/String;Ljava/lang/String;)I
    :try_end
    .catch Ljava/lang/NumberFormatException; {:try_start .. :try_end} :expected
    goto :fail

    :expected
    move-exception v0
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
