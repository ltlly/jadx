.class public final synthetic Linline/SyntheticValue;
.super Ljava/lang/Object;

.field private final synthetic value:Ljava/lang/String;

.method public synthetic constructor <init>(Ljava/lang/String;)V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Linline/SyntheticValue;->value:Ljava/lang/String;
    return-void
.end method

.method public final get()Ljava/lang/String;
    .registers 2
    iget-object v0, p0, Linline/SyntheticValue;->value:Ljava/lang/String;
    return-object v0
.end method
