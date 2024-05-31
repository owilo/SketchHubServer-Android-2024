package com.example.server

import java.security.MessageDigest

fun hashPassword(password: String): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
}