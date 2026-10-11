.class public Ltrycatch/TestTryCatchSharedOutside;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static test(ZLjava/lang/String;Ljava/lang/String;)I
    .registers 4
    if-eqz p0, :outside

    :try_start_first
    const-string v0, "1"
    invoke-static {v0}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    :try_end_first
    .catch Ljava/lang/NumberFormatException; {:try_start_first .. :try_end_first} :catch

    :outside
    # Both paths share this call, which is outside both protected ranges.
    invoke-static {p1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I

    :try_start_second
    invoke-static {p2}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v0
    :try_end_second
    .catch Ljava/lang/NumberFormatException; {:try_start_second .. :try_end_second} :catch
    return v0

    :catch
    move-exception v0
    const/4 v0, -0x1
    return v0
.end method

.method private static checkOutsideThrows(Z)V
    .registers 4
    const-string v0, "bad"
    const-string v1, "7"
    :try_start
    invoke-static {p0, v0, v1}, Ltrycatch/TestTryCatchSharedOutside;->test(ZLjava/lang/String;Ljava/lang/String;)I
    :try_end
    .catch Ljava/lang/NumberFormatException; {:try_start .. :try_end} :expected
    new-instance v2, Ljava/lang/AssertionError;
    # Report which branch swallowed the outside exception.
    invoke-direct {v2, p0}, Ljava/lang/AssertionError;-><init>(Z)V
    throw v2

    :expected
    move-exception v0
    return-void
.end method

.method private static checkNormal(Z)V
    .registers 4
    const-string v0, "7"
    invoke-static {p0, v0, v0}, Ltrycatch/TestTryCatchSharedOutside;->test(ZLjava/lang/String;Ljava/lang/String;)I
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
    .registers 2
    const/4 v0, 0x0
    invoke-static {v0}, Ltrycatch/TestTryCatchSharedOutside;->checkOutsideThrows(Z)V
    invoke-static {v0}, Ltrycatch/TestTryCatchSharedOutside;->checkNormal(Z)V
    const/4 v0, 0x1
    invoke-static {v0}, Ltrycatch/TestTryCatchSharedOutside;->checkOutsideThrows(Z)V
    invoke-static {v0}, Ltrycatch/TestTryCatchSharedOutside;->checkNormal(Z)V
    return-void
.end method
