.class public Linner/LambdaWrapper;
.super Ljava/lang/Object;

.method public static synthetic access$000()Ljava/util/function/Supplier;
    .registers 1

    invoke-custom {}, call_site_0("get", ()Ljava/util/function/Supplier;, ()Ljava/lang/Object;, invoke-static@Linner/LambdaWrapper;->lambda$0()Ljava/lang/String;, ()Ljava/lang/String;)@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
    move-result-object v0
    return-object v0
.end method

.method private static synthetic lambda$0()Ljava/lang/String;
    .registers 1

    const-string v0, "lambda body"
    return-object v0
.end method
