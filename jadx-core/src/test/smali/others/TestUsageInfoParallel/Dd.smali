.class public Lupa/Dd;
.super Lupa/BB;
.source "Dd.java"


# direct methods
.method public constructor <init>()V
	.registers 1

	invoke-direct {p0}, Lupa/BB;-><init>()V

	return-void
.end method


# virtual methods
.method public inheritedUse(I)I
	.registers 3
	.param p1, "value"    # I

	invoke-virtual {p0, p1}, Lupa/Dd;->util(I)I

	move-result v0

	return v0
.end method

.method public typeUse(Ljava/lang/Object;)Ljava/util/ArrayList;
	.registers 2
	.param p1, "obj"    # Ljava/lang/Object;

	check-cast p1, Ljava/util/ArrayList;

	return-object p1
.end method

.method public useConst()Ljava/lang/String;
	.registers 2

	.annotation runtime Ljava/lang/Deprecated;
	.end annotation

	sget-object v0, Lupa/Aa;->CONST:Ljava/lang/String;

	return-object v0
.end method
