.class public Lgenerics/TestGenericCatchBoundary;
.super Ljava/lang/Object;

.annotation system Ldalvik/annotation/Signature;
    value = {"<R:Ljava/lang/Object;>Ljava/lang/Object;"}
.end annotation

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public drain(Ljava/util/Iterator;Lgenerics/GenericSink;)V
    .locals 2
    .annotation system Ldalvik/annotation/Signature;
        value = {"(Ljava/util/Iterator<+TR;>;Lgenerics/GenericSink<-TR;>;)V"}
    .end annotation
    :try_start
    invoke-interface {p1}, Ljava/util/Iterator;->next()Ljava/lang/Object;
    move-result-object v0
    const-string v1, "The iterator returned a null value"
    invoke-static {v0, v1}, Lgenerics/TestGenericCatchBoundary;->requireNonNull(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;
    move-result-object v0
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch
    invoke-interface {p2, v0}, Lgenerics/GenericSink;->onNext(Ljava/lang/Object;)V
    return-void
    :catch
    move-exception v0
    invoke-interface {p2, v0}, Lgenerics/GenericSink;->onError(Ljava/lang/Throwable;)V
    return-void
.end method

.method public static requireNonNull(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;
    .locals 1
    .annotation system Ldalvik/annotation/Signature;
        value = {"<T:Ljava/lang/Object;>(TT;Ljava/lang/String;)TT;"}
    .end annotation
    if-nez p0, :non_null
    new-instance v0, Ljava/lang/NullPointerException;
    invoke-direct {v0, p1}, Ljava/lang/NullPointerException;-><init>(Ljava/lang/String;)V
    throw v0
    :non_null
    return-object p0
.end method

.method public check()V
    .locals 0
    .annotation system Ldalvik/annotation/Throws;
        value = {Ljava/lang/Exception;}
    .end annotation
    invoke-static {p0}, Ljadx/tests/integration/generics/TestGenericCatchBoundary;->check(Ljava/lang/Object;)V
    return-void
.end method
