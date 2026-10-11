.class public Ltrycatch/TestForceInlineCatchBoundary;
.super Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static test(ZLjava/lang/String;)I
    .registers 3
    if-eqz p0, :zero

    :try_start
    invoke-static {p1}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v0
    :try_end
    .catch Ljava/lang/NumberFormatException; {:try_start .. :try_end} :failed

    if-lez v0, :zero
    return v0

    :zero
    const/4 v0, 0x0
    return v0

    :failed
    move-exception p1
    const/4 v0, -0x1
    return v0
.end method

.method private static expect(ZLjava/lang/String;I)V
    .registers 4
    invoke-static {p0, p1}, Ltrycatch/TestForceInlineCatchBoundary;->test(ZLjava/lang/String;)I
    move-result v0
    if-ne v0, p2, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method

.method public check()V
    .registers 4
    const/4 v0, 0x1
    const-string v1, "bad"
    const/4 v2, -0x1
    invoke-static {v0, v1, v2}, Ltrycatch/TestForceInlineCatchBoundary;->expect(ZLjava/lang/String;I)V

    const/4 v0, 0x1
    const-string v1, "3"
    const/4 v2, 0x3
    invoke-static {v0, v1, v2}, Ltrycatch/TestForceInlineCatchBoundary;->expect(ZLjava/lang/String;I)V

    const/4 v0, 0x1
    const-string v1, "-2"
    const/4 v2, 0x0
    invoke-static {v0, v1, v2}, Ltrycatch/TestForceInlineCatchBoundary;->expect(ZLjava/lang/String;I)V

    const/4 v0, 0x0
    const-string v1, "bad"
    const/4 v2, 0x0
    invoke-static {v0, v1, v2}, Ltrycatch/TestForceInlineCatchBoundary;->expect(ZLjava/lang/String;I)V
    return-void
.end method
