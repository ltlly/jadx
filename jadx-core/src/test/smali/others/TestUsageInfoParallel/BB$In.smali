.class public Lupa/BB$In;
.super Ljava/lang/Object;
.source "BB.java"


# direct methods
.method public constructor <init>()V
	.registers 1

	invoke-direct {p0}, Ljava/lang/Object;-><init>()V

	return-void
.end method


# virtual methods
.method public callOuter(I)I
	.registers 4
	.param p1, "depth"    # I

	new-instance v0, Lupa/Aa;

	invoke-direct {v0}, Lupa/Aa;-><init>()V

	invoke-virtual {v0, p1}, Lupa/Aa;->recurse(I)I

	move-result v0

	return v0
.end method
