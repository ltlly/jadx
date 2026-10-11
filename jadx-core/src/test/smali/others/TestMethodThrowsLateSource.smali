.class public Lothers/TestMethodThrowsLateSource;
.super Ljava/lang/Object;

.method public static fail()V
    .registers 1
    new-instance v0, Ljava/io/FileNotFoundException;
    invoke-direct {v0}, Ljava/io/FileNotFoundException;-><init>()V
    throw v0
.end method

.method public static value()I
    .registers 1
    new-instance v0, Ljava/io/FileNotFoundException;
    invoke-direct {v0}, Ljava/io/FileNotFoundException;-><init>()V
    throw v0
.end method
