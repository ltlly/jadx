.class public Linline/ConstructorUser;
.super Ljava/lang/Object;

.method public static value()Ljava/lang/String;
    .registers 2
    new-instance v0, Linline/SyntheticValue;
    const-string v1, "kept"
    invoke-direct {v0, v1}, Linline/SyntheticValue;-><init>(Ljava/lang/String;)V
    invoke-static {v0}, Linline/ConstructorUser;->read(Linline/SyntheticValue;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

.method public static read(Linline/SyntheticValue;)Ljava/lang/String;
    .registers 1
    invoke-virtual {p0}, Linline/SyntheticValue;->get()Ljava/lang/String;
    move-result-object p0
    return-object p0
.end method
