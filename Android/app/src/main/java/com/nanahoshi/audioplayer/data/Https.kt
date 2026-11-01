package com.nanahoshi.audioplayer.data

fun String.toHttps(): String = when {
    startsWith("http://") -> "https://" + substring("http://".length)
    startsWith("//") -> "https:$this"
    else -> this
}
