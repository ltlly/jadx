.class public Ltrycatch/TestTryCatchOptionalEntry;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static test(ZLjava/lang/String;)I
    .registers 3
    # The false branch enters the same try after its first protected call.
    if-eqz p0, :second

    :try_start
    const-string v0, "1"
    invoke-static {v0}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I

    :second
    invoke-static {p1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v0
    :try_end
    .catch Ljava/lang/NumberFormatException; {:try_start .. :try_end} :catch
    return v0

    :catch
    move-exception v0
    const/4 v0, -0x1
    return v0
.end method

.method private static checkResult(ZLjava/lang/String;I)V
    .registers 4
    invoke-static {p0, p1}, Ltrycatch/TestTryCatchOptionalEntry;->test(ZLjava/lang/String;)I
    move-result v0
    if-eq v0, p2, :done
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0

    :done
    return-void
.end method

.method public check()V
    .registers 4
    const/4 v0, 0x0
    const-string v1, "bad"
    const/4 v2, -0x1
    invoke-static {v0, v1, v2}, Ltrycatch/TestTryCatchOptionalEntry;->checkResult(ZLjava/lang/String;I)V

    const/4 v0, 0x1
    invoke-static {v0, v1, v2}, Ltrycatch/TestTryCatchOptionalEntry;->checkResult(ZLjava/lang/String;I)V

    const-string v1, "7"
    const/4 v2, 0x7
    invoke-static {v0, v1, v2}, Ltrycatch/TestTryCatchOptionalEntry;->checkResult(ZLjava/lang/String;I)V

    const/4 v0, 0x0
    invoke-static {v0, v1, v2}, Ltrycatch/TestTryCatchOptionalEntry;->checkResult(ZLjava/lang/String;I)V
    return-void
.end method
