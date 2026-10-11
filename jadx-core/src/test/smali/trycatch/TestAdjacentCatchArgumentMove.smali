.class public Ltrycatch/TestAdjacentCatchArgumentMove;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static prepare(Z)I
    .registers 2
    if-eqz p0, :success
    new-instance v0, Ljava/io/IOException;
    invoke-direct {v0}, Ljava/io/IOException;-><init>()V
    throw v0
    :success
    const/4 v0, 0x7
    return v0
.end method

.method public static finish(IZ)I
    .registers 3
    if-eqz p1, :success
    new-instance v0, Ljava/io/IOException;
    invoke-direct {v0}, Ljava/io/IOException;-><init>()V
    throw v0
    :success
    return p0
.end method

.method public static run(IZZ)I
    .registers 6
    :prepare_start
    invoke-static {p1}, Ltrycatch/TestAdjacentCatchArgumentMove;->prepare(Z)I
    move-result v0
    :prepare_end
    .catch Ljava/io/IOException; {:prepare_start .. :prepare_end} :prepare_failed

    move v1, p0
    :finish_start
    invoke-static {v0, p2}, Ltrycatch/TestAdjacentCatchArgumentMove;->finish(IZ)I
    move-result v0
    :finish_end
    .catch Ljava/io/IOException; {:finish_start .. :finish_end} :finish_failed
    return v0

    :finish_failed
    move-exception v2
    goto :failed
    :prepare_failed
    move-exception v2
    move v1, p0
    :failed
    return v1
.end method

.method public check()V
    .registers 5
    const/16 v0, 0xc
    const/4 v1, 0x0
    invoke-static {v0, v1, v1}, Ltrycatch/TestAdjacentCatchArgumentMove;->run(IZZ)I
    move-result v3
    const/4 v2, 0x7
    if-ne v3, v2, :failed

    const/4 v2, 0x1
    invoke-static {v0, v1, v2}, Ltrycatch/TestAdjacentCatchArgumentMove;->run(IZZ)I
    move-result v3
    if-ne v3, v0, :failed

    invoke-static {v0, v2, v1}, Ltrycatch/TestAdjacentCatchArgumentMove;->run(IZZ)I
    move-result v3
    if-ne v3, v0, :failed
    return-void

    :failed
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
