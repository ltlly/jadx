.class public Ldst/AnnotatedUser;
.super Ljava/lang/Object;
.source "AnnotatedUser.java"


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public go(Ldst/Annotated;)V
    .registers 2

    invoke-interface {p1}, Ldst/Annotated;->act()V

    return-void
.end method
