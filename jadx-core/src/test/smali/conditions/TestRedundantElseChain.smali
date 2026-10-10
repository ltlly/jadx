.class public final Lconditions/TestRedundantElseChain;
.super Ljava/lang/Object;

.method public static test(I)I
    .locals 1

    if-eqz p0, :cond_0
    const/16 v0, 0x0
    return v0
    :cond_0
    if-eqz p0, :cond_1
    const/16 v0, 0x1
    return v0
    :cond_1
    if-eqz p0, :cond_2
    const/16 v0, 0x2
    return v0
    :cond_2
    if-eqz p0, :cond_3
    const/16 v0, 0x3
    return v0
    :cond_3
    if-eqz p0, :cond_4
    const/16 v0, 0x4
    return v0
    :cond_4
    if-eqz p0, :cond_5
    const/16 v0, 0x5
    return v0
    :cond_5
    if-eqz p0, :cond_6
    const/16 v0, 0x6
    return v0
    :cond_6
    if-eqz p0, :cond_7
    const/16 v0, 0x7
    return v0
    :cond_7
    if-eqz p0, :cond_8
    const/16 v0, 0x8
    return v0
    :cond_8
    if-eqz p0, :cond_9
    const/16 v0, 0x9
    return v0
    :cond_9
    const/4 v0, -0x1
    return v0
.end method
