# Release builds keep no debug/info logging.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# jsch loads its algorithms by class name from its config (kex, ciphers, MACs, key types).
-keep class com.jcraft.jsch.** { *; }
# Optional jsch back ends that Varco does not use (agent sockets, GSS-API, logging bridges).
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
