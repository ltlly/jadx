.class public Lothers/TestMethodThrowsLateCaller;
.super Ljava/lang/Object;

.method public static ordinary()V
    .registers 0
    invoke-static {}, Lothers/TestMethodThrowsLateSource;->fail()V
    return-void
.end method

.method public static wrappedReturn()I
    .registers 1
    invoke-static {}, Lothers/TestMethodThrowsLateSource;->value()I
    move-result v0
    return v0
.end method

.method public static caught()V
    .registers 1
    :try_start
    invoke-static {}, Lothers/TestMethodThrowsLateSource;->fail()V
    :try_end
    .catch Ljava/io/IOException; {:try_start .. :try_end} :handler
    return-void

    :handler
    move-exception v0
    return-void
.end method

.method public static rethrow()V
    .registers 1
    :try_start
    invoke-static {}, Lothers/TestMethodThrowsLateSource;->fail()V
    :try_end
    .catch Ljava/io/IOException; {:try_start .. :try_end} :handler
    return-void

    :handler
    move-exception v0
    throw v0
.end method
