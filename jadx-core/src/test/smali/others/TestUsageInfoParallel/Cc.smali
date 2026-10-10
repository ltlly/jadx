.class public Lupa/Cc;
.super Ljava/lang/Object;
.source "Cc.java"

# interfaces
.implements Lupa/If;


# direct methods
.method public constructor <init>()V
	.registers 1

	invoke-direct {p0}, Ljava/lang/Object;-><init>()V

	return-void
.end method


# virtual methods
.method public interfaceCall(Lupa/If;)I
	.registers 3
	.param p1, "impl"    # Lupa/If;

	invoke-interface {p1}, Lupa/If;->ifMth()I

	move-result v0

	return v0
.end method
