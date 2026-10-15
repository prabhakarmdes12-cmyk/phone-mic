# Preserve JNI export methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve native engine classes
-keep class com.chiti.phonemic.audio.NativeAudioEngine { *; }
