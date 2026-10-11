.class public Ltrycatch/TestCatchBoxedReturn;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method private static parse(Ljava/lang/String;)Z
    .registers 3
    const-string v0, "bad"
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :parse
    new-instance v0, Ljava/lang/IllegalArgumentException;
    invoke-direct {v0}, Ljava/lang/IllegalArgumentException;-><init>()V
    throw v0
    :parse
    invoke-static {p0}, Ljava/lang/Boolean;->parseBoolean(Ljava/lang/String;)Z
    move-result v0
    return v0
.end method

.method public static test(Ljava/lang/String;)Ljava/lang/Boolean;
    .registers 2
    if-eqz p0, :null
    invoke-virtual {p0}, Ljava/lang/String;->isEmpty()Z
    move-result v0
    if-nez v0, :null
    :try_start
    invoke-static {p0}, Ltrycatch/TestCatchBoxedReturn;->parse(Ljava/lang/String;)Z
    move-result v0
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :failed
    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
    move-result-object v0
    goto :return
    :failed
    move-exception v0
    :null
    const/4 v0, 0x0
    :return
    return-object v0
.end method

.method public check()V
    .registers 3
    const-string v0, "true"
    invoke-static {v0}, Ltrycatch/TestCatchBoxedReturn;->test(Ljava/lang/String;)Ljava/lang/Boolean;
    move-result-object v0
    sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
    if-ne v0, v1, :fail
    const-string v0, "false"
    invoke-static {v0}, Ltrycatch/TestCatchBoxedReturn;->test(Ljava/lang/String;)Ljava/lang/Boolean;
    move-result-object v0
    sget-object v1, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
    if-ne v0, v1, :fail
    const-string v0, "bad"
    invoke-static {v0}, Ltrycatch/TestCatchBoxedReturn;->test(Ljava/lang/String;)Ljava/lang/Boolean;
    move-result-object v0
    if-nez v0, :fail
    const/4 v0, 0x0
    invoke-static {v0}, Ltrycatch/TestCatchBoxedReturn;->test(Ljava/lang/String;)Ljava/lang/Boolean;
    move-result-object v0
    if-nez v0, :fail
    const-string v0, ""
    invoke-static {v0}, Ltrycatch/TestCatchBoxedReturn;->test(Ljava/lang/String;)Ljava/lang/Boolean;
    move-result-object v0
    if-nez v0, :fail
    return-void
    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
