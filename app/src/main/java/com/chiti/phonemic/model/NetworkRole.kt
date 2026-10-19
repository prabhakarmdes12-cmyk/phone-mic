package com.chiti.phonemic.model

enum class NetworkRole(val label: String, val description: String) {
    STANDALONE("Single Phone", "Use this phone as a direct mic to speaker"),
    TRANSMITTER("Wireless Mic", "Send voice to a Speaker Station"),
    RECEIVER_MIXER("Speaker Hub (Multi-Mic)", "Receive and mix up to eight phone mics"),
    WALKIE_TALKIE("Walkie-Talkie Mesh", "Push-to-talk group intercom on local Wi-Fi");
    companion object { val ALL: List<NetworkRole> = values().toList() }
}
