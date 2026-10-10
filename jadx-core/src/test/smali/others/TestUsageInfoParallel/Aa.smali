.class public Lupa/Aa;
.super Ljava/lang/Object;
.source "Aa.java"

# interfaces
.implements Lupa/If;


# static fields
.field public static final CONST:Ljava/lang/String; = "const-value"


# direct methods
.method public constructor <init>()V
	.registers 1

	invoke-direct {p0}, Ljava/lang/Object;-><init>()V

	return-void
.end method


# virtual methods
.method public annotated()V
	.registers 1

	.annotation runtime Ljava/lang/Deprecated;
	.end annotation

	return-void
.end method

.method public callMissing()V
	.registers 1

	invoke-static {}, Lmissing/Missing;->gone()V

	return-void
.end method

.method public ifMth()I
	.registers 2

	sget v0, Lupa/BB;->shared:I

	invoke-static {v0}, Lupa/BB;->util(I)I

	move-result v0

	return v0
.end method

.method public recurse(I)I
	.registers 3
	.param p1, "depth"    # I

	if-lez p1, :cond_0

	add-int/lit8 v0, p1, -0x1

	invoke-virtual {p0, v0}, Lupa/Aa;->recurse(I)I

	move-result v0

	return v0

	:cond_0
	const/4 v0, 0x0

	return v0
.end method

.method public useOverloads(Z)V
	.registers 3
	.param p1, "flag"    # Z

	invoke-static {p1}, Lupa/BB;->overload(Z)V

	const/4 v0, 0x7

	invoke-static {v0}, Lupa/BB;->overload(I)V

	return-void
.end method
