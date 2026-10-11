.class public Ltrycatch/TestCatchPhiMethodArgument;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static attempt(Z)V
    .registers 3
    if-eqz p0, :return
    new-instance v0, Ljava/io/IOException;
    const-string v1, "replacement"
    invoke-direct {v0, v1}, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
    throw v0
    :return
    return-void
.end method

.method public static select(Ljava/io/IOException;Z)Ljava/io/IOException;
    .registers 2
    :try_start
    invoke-static {p1}, Ltrycatch/TestCatchPhiMethodArgument;->attempt(Z)V
    :try_end
    .catch Ljava/io/IOException; {:try_start .. :try_end} :caught
    goto :return
    :caught
    move-exception p0
    :return
    return-object p0
.end method

.method public check()V
    .registers 4
    new-instance v0, Ljava/io/IOException;
    invoke-direct {v0}, Ljava/io/IOException;-><init>()V
    const/4 v1, 0x0
    invoke-static {v0, v1}, Ltrycatch/TestCatchPhiMethodArgument;->select(Ljava/io/IOException;Z)Ljava/io/IOException;
    move-result-object v2
    if-ne v2, v0, :failed

    const/4 v1, 0x1
    invoke-static {v0, v1}, Ltrycatch/TestCatchPhiMethodArgument;->select(Ljava/io/IOException;Z)Ljava/io/IOException;
    move-result-object v2
    if-eq v2, v0, :failed
    invoke-virtual {v2}, Ljava/io/IOException;->getMessage()Ljava/lang/String;
    move-result-object v2
    const-string v0, "replacement"
    invoke-virtual {v0, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :failed
    return-void

    :failed
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
