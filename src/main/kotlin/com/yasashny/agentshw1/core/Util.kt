package com.yasashny.agentshw1.core

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

fun sha256(file: File): String = sha256(file.readBytes())

fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

fun nowIso(): String = Instant.now().toString()
