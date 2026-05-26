package com.p2p.camera

data object Contacts

data class Call(val peerAddress: String, val isIncoming: Boolean = false)

data class TextChat(val peerAddress: String, val peerLabel: String = "")
