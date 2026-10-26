package com.vscodroid.diligent.preview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewUrlsTest {

    @Test
    fun `the preview address`() {
        assertEquals("http://localhost:3000/", PreviewUrls.forPort(3000))
        assertEquals("localhost:5173", PreviewUrls.label(5173))
    }

    @Test
    fun `loopback addresses load inside`() {
        for (url in listOf(
            "http://localhost:3000/",
            "http://LOCALHOST:3000/about?x=1#y",
            "http://127.0.0.1:8000/docs",
            "https://localhost:3000",
            "http://[::1]:3000/",
            "http://app.localhost:3000/",
        )) assertTrue(PreviewUrls.isLocal(url), url)
    }

    @Test
    fun `everything else goes to the browser`() {
        for (url in listOf(
            "https://nextjs.org/docs",
            "http://localhost.evil.com/",
            "http://127.0.0.1.nip.io/",
            "http://192.168.1.2:3000/",
            "file:///sdcard/x.html",
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "mailto:a@b.c",
            "not a url",
            "",
        )) assertFalse(PreviewUrls.isLocal(url), url)
    }
}
