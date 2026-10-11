.class public Lothers/TestMethodThrowsCycle;
.super Ljava/lang/Object;

.method public static first()V
    .registers 2
    invoke-static {}, Lothers/TestMethodThrowsCycle;->second()V
    new-instance v0, Ljava/io/FileNotFoundException;
    const-string v1, ""
    invoke-direct {v0, v1}, Ljava/io/FileNotFoundException;-><init>(Ljava/lang/String;)V
    throw v0
.end method

.method public static second()V
    .registers 0
    invoke-static {}, Lothers/TestMethodThrowsCycle;->first()V
    return-void
.end method
