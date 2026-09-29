package com.nanahoshi.audioplayer.data

fun String.toHttps(): String =
    if (startsWith("http://")) "https://" + substring("http://".length) else this
