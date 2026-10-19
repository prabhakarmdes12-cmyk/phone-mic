package com.chiti.phonemic.model

enum class NetworkRole {
    STANDALONE, TRANSMITTER, RECEIVER;

    companion object {
        val ALL: List<NetworkRole> = values().toList()
    }
}
