.class public Ltrycatch/TestCatchBoundaryNamedConstant;
.super Ljava/lang/Object;

.field private static final ZERO:D = 0.0
.field private static final ZZZ:D = 1.0

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static check(Ljava/lang/Double;)Z
    .registers 6
    .param p0, "value"

    const/4 v0, 0x0
    :try_start
    invoke-virtual {p0}, Ljava/lang/Double;->doubleValue()D
    move-result-wide v1
    :try_end
    .catch Ljava/lang/NullPointerException; {:try_start .. :try_end} :catch
    const-wide/16 v3, 0x0
    cmpl-double v1, v1, v3
    if-eqz v1, :done
    const/4 v0, 0x1
    :done
    return v0

    :catch
    move-exception v1
    return v0
.end method

.method public check()V
    .registers 4
    const/4 v0, 0x0
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryNamedConstant;->check(Ljava/lang/Double;)Z
    move-result v0
    if-nez v0, :fail

    const-wide/16 v0, 0x0
    invoke-static {v0, v1}, Ljava/lang/Double;->valueOf(D)Ljava/lang/Double;
    move-result-object v0
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryNamedConstant;->check(Ljava/lang/Double;)Z
    move-result v0
    if-nez v0, :fail

    const-wide/high16 v0, -0x8000000000000000L
    invoke-static {v0, v1}, Ljava/lang/Double;->valueOf(D)Ljava/lang/Double;
    move-result-object v0
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryNamedConstant;->check(Ljava/lang/Double;)Z
    move-result v0
    if-nez v0, :fail

    const-wide/high16 v0, 0x3ff0000000000000L
    invoke-static {v0, v1}, Ljava/lang/Double;->valueOf(D)Ljava/lang/Double;
    move-result-object v0
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryNamedConstant;->check(Ljava/lang/Double;)Z
    move-result v0
    if-eqz v0, :fail

    const-wide/high16 v0, 0x7ff8000000000000L
    invoke-static {v0, v1}, Ljava/lang/Double;->valueOf(D)Ljava/lang/Double;
    move-result-object v0
    invoke-static {v0}, Ltrycatch/TestCatchBoundaryNamedConstant;->check(Ljava/lang/Double;)Z
    move-result v0
    if-eqz v0, :fail
    return-void

    :fail
    new-instance v0, Ljava/lang/AssertionError;
    invoke-direct {v0}, Ljava/lang/AssertionError;-><init>()V
    throw v0
.end method
