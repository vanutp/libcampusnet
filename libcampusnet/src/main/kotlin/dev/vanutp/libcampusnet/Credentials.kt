package dev.vanutp.libcampusnet

import kotlinx.serialization.Serializable

@Serializable
data class LoginCredentials(
    val username: String,
    val password: String,
)

@Serializable
data class DsfSessionCredentials(
    val idsrv: String,
    val idsrvC1: String,
    val idsrvC2: String,
)

@Serializable
data class CnetSessionCredentials(
    val sid: String,
    val cnsc: String,
)
