package io.searchhub.collector.interfaces

interface SessionStore {
    suspend fun getOrCreateSessionId(): String
    suspend fun touch()
}

private val SESSION_ID_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9')

fun generateSessionId(): String = (1..7).map { SESSION_ID_CHARS.random() }.joinToString("")
