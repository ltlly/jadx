.class public Ltrycatch/TestTryCatchOptionalEntryOutside;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static test(ZLjava/lang/String;Ljava/lang/String;)I
    .registers 4
    if-nez p0, :try_start

    # This call is outside the catch range, even though it can throw the caught type.
    invoke-static {p1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    goto :second

    :try_start
    const-string v0, "1"
    invoke-static {v0}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I

    :second
    invoke-static {p2}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v0
    :try_end
    .catch Ljava/lang/NumberFormatException; {:try_start .. :try_end} :catch
    return v0

    :catch
    move-exception v0
    const/4 v0, -0x1
    return v0
.end method

.method private static checkOutsideThrows()V
    .registers 3
    const/4 v0, 0x0
    const-string v1, "bad"
    const-string v2, "7"
    :try_start
    invoke-static {v0, v1, v2}, Ltrycatch/TestTryCatchOptionalEntryOutside;->test(ZLjava/lang/String;Ljava/lang/String;)I
    :try_end
    .catch Ljava/lang/NumberFormatException; {:try_start .. :try_end} :expected
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :expected
    move-exception v0
    return-void
.end method

.method private static checkResult(ZLjava/lang/String;Ljava/lang/String;I)V
    .registers 5
    invoke-static {p0, p1, p2}, Ltrycatch/TestTryCatchOptionalEntryOutside;->test(ZLjava/lang/String;Ljava/lang/String;)I
    move-result v0
    if-eq v0, p3, :done
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :done
    return-void
.end method

.method public check()V
    .registers 5
    invoke-static {}, Ltrycatch/TestTryCatchOptionalEntryOutside;->checkOutsideThrows()V

    const/4 v0, 0x0
    const-string v1, "7"
    const-string v2, "7"
    const/4 v3, 0x7
    invoke-static {v0, v1, v2, v3}, Ltrycatch/TestTryCatchOptionalEntryOutside;->checkResult(ZLjava/lang/String;Ljava/lang/String;I)V

    const/4 v0, 0x1
    const-string v1, "bad"
    const-string v2, "7"
    const/4 v3, 0x7
    invoke-static {v0, v1, v2, v3}, Ltrycatch/TestTryCatchOptionalEntryOutside;->checkResult(ZLjava/lang/String;Ljava/lang/String;I)V

    return-void
.end method
