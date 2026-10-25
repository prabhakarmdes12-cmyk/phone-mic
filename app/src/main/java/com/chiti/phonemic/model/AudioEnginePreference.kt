package com.chiti.phonemic.model

enum class AudioEnginePreference(val label: String, val description: String) {
    AUTO("Auto (Recommended)", "Low-latency native on Android 12+, stable platform engine on Android 11"),
    PLATFORM_JAVA("Platform AudioRecord (Maximum Stability)", "Rock-solid Google platform audio pipeline for all devices"),
    NATIVE_OBOE("Native C++ (Ultra Low-Latency)", "AAudio / Oboe hardware pipeline for lowest audio roundtrip");

    companion object {
        val ALL: List<AudioEnginePreference> = values().toList()
    }
}
