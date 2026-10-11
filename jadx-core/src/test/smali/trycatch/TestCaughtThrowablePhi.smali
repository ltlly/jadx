.class public Ltrycatch/TestCaughtThrowablePhi;
.super Ljava/lang/Object;

.method public static read(Ljava/lang/String;)I
    .registers 6
    .param p0, "path"
    const/4 v0, 0x0
    const/4 v1, 0x0

    :try_open
    invoke-static {p0}, Ltrycatch/TestCaughtThrowablePhi;->open(Ljava/lang/String;)Ljava/io/InputStream;
    move-result-object v3
    :end_open
    .catch Ljava/io/IOException; {:try_open .. :end_open} :caught_open
    .catchall {:try_open .. :end_open} :catchall_open
    if-eqz v3, :close_normal

    :try_read
    invoke-virtual {v3}, Ljava/io/InputStream;->read()I
    move-result v0
    :end_read
    .catch Ljava/io/IOException; {:try_read .. :end_read} :caught_read
    .catchall {:try_read .. :end_read} :catchall_read
    goto :close_normal

    :catchall_read
    move-exception p0
    move-object v1, v3
    goto :close_rethrow

    :caught_read
    move-exception v2
    invoke-virtual {v2}, Ljava/lang/Throwable;->printStackTrace()V
    goto :close_normal

    :close_normal
    invoke-static {v3}, Ltrycatch/TestCaughtThrowablePhi;->closeQuietly(Ljava/io/InputStream;)V
    goto :return

    :catchall_open
    move-exception p0
    goto :close_rethrow

    :caught_open
    move-exception v2
    invoke-virtual {v2}, Ljava/lang/Throwable;->printStackTrace()V
    move-object v3, v1
    goto :close_normal

    :return
    return v0

    :close_rethrow
    if-eqz v1, :throw
    invoke-static {v1}, Ltrycatch/TestCaughtThrowablePhi;->closeQuietly(Ljava/io/InputStream;)V

    :throw
    throw p0
.end method

.method public static open(Ljava/lang/String;)Ljava/io/InputStream;
    .registers 2
    .param p0, "path"
    .annotation system Ldalvik/annotation/Throws;
        value = {Ljava/io/FileNotFoundException;}
    .end annotation
    new-instance v0, Ljava/io/FileInputStream;
    invoke-direct {v0, p0}, Ljava/io/FileInputStream;-><init>(Ljava/lang/String;)V
    return-object v0
.end method

.method public static closeQuietly(Ljava/io/InputStream;)V
    .registers 2
    .param p0, "stream"
    if-eqz p0, :return_close
    :try_close
    invoke-virtual {p0}, Ljava/io/InputStream;->close()V
    :end_close
    .catch Ljava/io/IOException; {:try_close .. :end_close} :caught_close
    goto :return_close
    :caught_close
    move-exception v0
    invoke-virtual {v0}, Ljava/lang/Throwable;->printStackTrace()V
    :return_close
    return-void
.end method
