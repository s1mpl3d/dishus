# JNA + UniFFI generated bindings are reached reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.dishus_core.** { *; }
-dontwarn java.awt.**

# JNI entry point implemented in Rust (crates/dishus-core/src/android.rs)
-keep class org.dishus.app.NativeInit { *; }
-dontwarn org.bouncycastle.**

# JSch loads its ciphers/KEX by reflection.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
