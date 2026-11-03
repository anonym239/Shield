# gomobile generated bindings (Go core) are called via JNI
-keep class go.** { *; }
-keep class shieldcore.** { *; }
-keep class dev.shield.core.vpn.GoBridge { *; }
# zxing
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.errorprone.annotations.**
