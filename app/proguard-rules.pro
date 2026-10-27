-dontwarn io.netty.**
-keep class io.netty.** { *; }

-keep class com.vauth.foxyvpn.vpn.tun.HevSocks5Tunnel { *; }
-keepnames class com.vauth.foxyvpn.vpn.tun.HevSocks5Tunnel
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

-dontwarn io.netty.internal.tcnative.**
-dontwarn io.netty.handler.ssl.OpenSsl**

-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn com.google.errorprone.annotations.**

-dontwarn reactor.blockhound.**

# Privacy: debug logging may contain destination hostnames, so strip those calls (and their string
# building) from release builds.
-assumenosideeffects class com.vauth.foxyvpn.data.AppLogger {
    public void d(java.lang.String, java.lang.String);
}
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
