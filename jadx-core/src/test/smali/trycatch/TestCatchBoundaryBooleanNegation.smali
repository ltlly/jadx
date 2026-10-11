.class public Ltrycatch/TestCatchBoundaryBooleanNegation;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method private static isBlank(Ljava/lang/String;)Z
    .registers 1
    invoke-virtual {p0}, Ljava/lang/String;->isEmpty()Z
    move-result p0
    return p0
.end method

.method public static test(Ljava/lang/String;)Z
    .registers 2
    .param p0, "value"
    :try_start
    invoke-static {p0}, Ltrycatch/TestCatchBoundaryBooleanNegation;->isBlank(Ljava/lang/String;)Z
    move-result v0
    :try_end
    .catch Ljava/lang/NullPointerException; {:try_start .. :try_end} :failed
    xor-int/lit8 v0, v0, 0x1
    return v0
    :failed
    move-exception v0
    const/4 v0, 0x0
    return v0
.end method

.method public check()V
    .registers 2
    const-string v0, "a"
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryBooleanNegation;->test(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :fail
    const-string v0, ""
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryBooleanNegation;->test(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :fail
    const/4 v0, 0x0
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryBooleanNegation;->test(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :fail
    return-void
    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
