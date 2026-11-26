package com.nanahoshi.audioplayer.data

fun String.toHttps(): String = when {
    startsWith("//") -> "https:$this"
    regionMatches(0, "http://", 0, "http://".length, ignoreCase = true) -> "https://" + substring("http://".length)
    else -> this
}
