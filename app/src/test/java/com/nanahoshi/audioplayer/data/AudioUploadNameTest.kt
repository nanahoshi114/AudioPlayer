package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioUploadNameTest {
    @Test
    fun keepsRecognizedExtension() {
        assertEquals("track.mp3", audioUploadName("track.MP3", byteArrayOf()))
        assertEquals("voice.wav", audioUploadName("voice.wav", byteArrayOf()))
    }

    @Test
    fun addsExtensionFromFileHeader() {
        assertEquals("f42eddd3.mp3", audioUploadName("f42eddd3", "ID3".encodeToByteArray()))
        assertEquals("clip.flac", audioUploadName("clip", "fLaC".encodeToByteArray()))
        assertEquals("song.m4a", audioUploadName("song", byteArrayOf(0, 0, 0, 0) + "ftyp".encodeToByteArray()))
    }
}
