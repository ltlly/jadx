.class public Ltypes/TestConstPhiNull;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static test(Z)Ljava/lang/String;
    .registers 3
    if-eqz p0, :else
    const/4 v0, 0x0
    const-string v1, "yes"
    goto :join

    :else
    const/4 v0, 0x0
    const-string v1, "no"

    :join
    # Both incoming values are null, usable with either unrelated reference type.
    invoke-static {v0, v0, v1}, Ltypes/TestConstPhiNull;->use(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

.method private static use(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;)Ljava/lang/String;
    .registers 4
    if-nez p0, :fail
    if-nez p1, :fail
    return-object p2

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method

.method public static testCatch(Z)Ljava/lang/String;
    .registers 4
    const/4 v0, 0x0
    const/4 v2, 0x0
    if-eqz p0, :else
    const-string v1, "yes"
    goto :try_start

    :else
    const-string v1, "no"

    :try_start
    # The handler merges the constants before and inside this try.
    const/4 v0, 0x0
    invoke-static {v1}, Ltypes/TestConstPhiNull;->fail(Ljava/lang/String;)V

    :try_end
    .catch Ljava/lang/IllegalStateException; {:try_start .. :try_end} :handler
    goto :join

    :handler
    move-exception v2
    invoke-static {v0, v0, v1}, Ltypes/TestConstPhiNull;->use(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1

    :join
    # The exception register joins its initial null, requiring a catch-variable move.
    if-nez v2, :caught
    const-string v1, "normal"
    :caught
    return-object v1
.end method

.method private static fail(Ljava/lang/String;)V
    .registers 2
    new-instance v0, Ljava/lang/IllegalStateException;
    invoke-direct {v0, p0}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V
    throw v0
.end method

.method public check()V
    .registers 3
    const/4 v0, 0x1
    invoke-static {v0}, Ltypes/TestConstPhiNull;->test(Z)Ljava/lang/String;
    move-result-object v0
    const-string v1, "yes"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :fail

    const/4 v0, 0x0
    invoke-static {v0}, Ltypes/TestConstPhiNull;->test(Z)Ljava/lang/String;
    move-result-object v0
    const-string v1, "no"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :fail

    const/4 v0, 0x1
    invoke-static {v0}, Ltypes/TestConstPhiNull;->testCatch(Z)Ljava/lang/String;
    move-result-object v0
    const-string v1, "yes"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :fail

    const/4 v0, 0x0
    invoke-static {v0}, Ltypes/TestConstPhiNull;->testCatch(Z)Ljava/lang/String;
    move-result-object v0
    const-string v1, "no"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
