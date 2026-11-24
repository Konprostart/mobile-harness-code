package com.mobileharness.code.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserGuardTest {

    @Test fun allowsPublicHttps() {
        assertEquals(BrowserGuard.UrlDecision.ALLOWED, BrowserGuard.classify("https://example.com/page"))
        assertEquals(BrowserGuard.UrlDecision.ALLOWED, BrowserGuard.classify("https://en.wikipedia.org/wiki/Kotlin"))
    }

    @Test fun blocksNonHttpSchemes() {
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_SCHEME, BrowserGuard.classify("file:///etc/passwd"))
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_SCHEME, BrowserGuard.classify("javascript:alert(1)"))
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_SCHEME, BrowserGuard.classify("content://settings"))
    }

    @Test fun blocksSsrfHosts() {
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_SSRF, BrowserGuard.classify("https://169.254.169.254/latest/meta-data"))
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_SSRF, BrowserGuard.classify("https://127.0.0.1/admin"))
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_SSRF, BrowserGuard.classify("https://10.0.0.5/"))
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_SSRF, BrowserGuard.classify("https://192.168.1.1/"))
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_SSRF, BrowserGuard.classify("https://[::1]/"))
    }

    @Test fun blocksUserinfoAndInvalidAndTooLong() {
        assertEquals(BrowserGuard.UrlDecision.BLOCKED_USERINFO, BrowserGuard.classify("https://user:pass@example.com/"))
        assertEquals(BrowserGuard.UrlDecision.INVALID, BrowserGuard.classify("not a url"))
        assertEquals(BrowserGuard.UrlDecision.TOO_LONG, BrowserGuard.classify("https://example.com/" + "a".repeat(3000)))
    }

    @Test fun downloadLimitsAndContentTypes() {
        assertTrue(BrowserGuard.isDownloadAllowed(1_000_000, "application/pdf"))
        assertFalse(BrowserGuard.isDownloadAllowed(BrowserGuard.MAX_DOWNLOAD_BYTES + 1, "application/pdf"))
        assertFalse(BrowserGuard.isDownloadAllowed(100, "application/vnd.android.package-archive"))
    }
}

class BrowserSessionTest {

    @Test fun openNavigateAndHistory() {
        val session = BrowserSession()
        val tab = session.openTab()
        assertTrue(session.navigate("https://example.com"))
        assertTrue(session.navigate("https://example.com/page2"))
        assertEquals("https://example.com/page2", tab.currentUrl)
        assertTrue(tab.canGoBack)

        assertEquals("https://example.com", session.back())
        assertTrue(tab.canGoForward)
        assertEquals("https://example.com/page2", session.forward())
    }

    @Test fun blockedUrlIsNotNavigated() {
        val session = BrowserSession()
        val tab = session.openTab()
        assertFalse(session.navigate("https://169.254.169.254/meta-data"))
        assertEquals("", tab.currentUrl)
        assertEquals(0, tab.history.size)
    }

    @Test fun backForwardTruncatesOnNewNavigation() {
        val session = BrowserSession()
        val tab = session.openTab()
        session.navigate("https://a.com")
        session.navigate("https://b.com")
        session.back() // back to a.com
        session.navigate("https://c.com") // forward history truncated
        assertFalse(tab.canGoForward)
        assertEquals("https://c.com", tab.currentUrl)
        assertEquals(listOf("https://a.com", "https://c.com"), tab.history.toList())
    }

    @Test fun multipleTabsAreIsolated() {
        val session = BrowserSession()
        val tab1 = session.openTab("https://a.com")
        val tab2 = session.openTab("https://b.com")
        assertEquals(2, session.size())
        assertNull(session.back(tab1.id)) // tab1 has no history to go back to
        session.closeTab(tab2.id)
        assertEquals(1, session.size())
    }

    @Test fun contentIsBounded() {
        val session = BrowserSession(maxContentChars = 10)
        val tab = session.openTab()
        session.setContent(tab.id, "Title", "x".repeat(1000))
        assertEquals(10, tab.content.length)
    }
}
