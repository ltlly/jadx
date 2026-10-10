.class public Lupa/BB;
.super Ljava/lang/Object;
.source "BB.java"


# static fields
.field public static shared:I


# direct methods
.method public constructor <init>()V
	.registers 1

	invoke-direct {p0}, Ljava/lang/Object;-><init>()V

	return-void
.end method

.method public static util(I)I
	.registers 2
	.param p0, "value"    # I

	add-int/lit8 v0, p0, 0x1

	return v0
.end method

.method public static overload(I)V
	.registers 1
	.param p0, "value"    # I

	return-void
.end method

.method public static overload(Z)V
	.registers 1
	.param p0, "flag"    # Z

	return-void
.end method
