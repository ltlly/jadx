.class public Lothers/TestMethodThrowsForeign;
.super Ljava/lang/Object;

.method public static test(Lothers/TestMissingExceptions;)V
    .registers 1
    .param p0, "source"
    invoke-virtual {p0}, Lothers/TestMissingExceptions;->missingThrowsAnnotation()V
    return-void
.end method
