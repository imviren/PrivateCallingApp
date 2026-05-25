# WebRTC JNI keeping
-keep class org.webrtc.** { *; }

# Dagger Hilt keeping
-keep class com.p2p.** { *; }
-keepclassmembers class * {
    @dagger.hilt.** *;
}

# Ktor network serializations and Netty keep rules
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.serialization.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
