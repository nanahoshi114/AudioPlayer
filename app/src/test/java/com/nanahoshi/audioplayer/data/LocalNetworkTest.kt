package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun privateAddressesNeedLocalAccess() {
        assertTrue(isLocalNetworkAddress("http://192.168.31.52:8765"))
        assertTrue(isLocalNetworkAddress("http://10.0.0.8:11434/v1"))
        assertTrue(isLocalNetworkAddress("http://127.0.0.1:8765"))
        assertTrue(isLocalNetworkAddress("http://desktop:8765"))
        assertTrue(isLocalNetworkAddress("http://nas.local:8765"))
    }

    @Test
    fun publicAddressesStayOnTheInternet() {
        assertFalse(isLocalNetworkAddress("https://api.openai.com/v1"))
        assertFalse(isLocalNetworkAddress("https://maas.example.com/compatible-mode/v1"))
        assertFalse(isLocalNetworkAddress(null))
        assertFalse(isLocalNetworkAddress("不是地址"))
    }
}
