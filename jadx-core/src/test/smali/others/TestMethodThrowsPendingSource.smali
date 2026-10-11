.class public Lothers/TestMethodThrowsPendingSource;
.super Ljava/lang/Object;

.method public static call()V
    .registers 1
    invoke-static {}, Lothers/TestMethodThrowsPendingHelper;->noop()V
    new-instance v0, Ljava/io/FileNotFoundException;
    invoke-direct {v0}, Ljava/io/FileNotFoundException;-><init>()V
    throw v0
.end method
