# Keep BC provider (we reference it and it registers algorithms)
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }

# Keep ChaCha20-Poly1305 JCA wiring (registered via reflection)
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha** { *; }