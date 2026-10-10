.class public interface abstract Ldst/Sig;
.super Ljava/lang/Object;
.source "Sig.java"


# direct methods
.method public abstract checked(Ljava/lang/String;)V
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/io/IOException;,
            Ljava/lang/String;,
            Lmissing/UnknownException;
        }
    .end annotation
.end method

.method public abstract other()V
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/io/IOException;,
            Ljava/lang/InterruptedException;
        }
    .end annotation
.end method
