.class public Lgetters/reload/Base;
.super Ljava/lang/Object;

# Reuse valid class metadata; the getter alias comes from the instance field value.
.annotation runtime Lkotlin/Metadata;
    d1 = {
        "\u0000\u0010\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\u0010\u000e\n\u0002\u0008\u0004\u0008\u0086\u0003\u0018\u00002\u00020\u0001B\t\u0008\u0002\u00a2\u0006\u0004\u0008\u0004\u0010\u0005J\u0006\u0010\u0003\u001a\u00020\u0002\u00a8\u0006\u0006"
    }
    d2 = {
        "Lgetters/reload/Base;",
        "",
        "",
        "a",
        "<init>",
        "()V",
        "app_release"
    }
    k = 0x1
    mv = {
        0x1,
        0x8,
        0x0
    }
.end annotation

.field private final value:Ljava/lang/String;
.field private count:I
.field private final objectValue:Ljava/lang/Object;

.method public constructor <init>(Ljava/lang/String;)V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lgetters/reload/Base;->value:Ljava/lang/String;
    iput-object p1, p0, Lgetters/reload/Base;->objectValue:Ljava/lang/Object;
    return-void
.end method

.method public a()Ljava/lang/String;
    .registers 2
    iget-object v0, p0, Lgetters/reload/Base;->value:Ljava/lang/String;
    return-object v0
.end method

.method public b()I
    .registers 2
    iget v0, p0, Lgetters/reload/Base;->count:I
    return v0
.end method

.method public c()Ljava/lang/Object;
    .registers 2
    iget-object v0, p0, Lgetters/reload/Base;->objectValue:Ljava/lang/Object;
    return-object v0
.end method
