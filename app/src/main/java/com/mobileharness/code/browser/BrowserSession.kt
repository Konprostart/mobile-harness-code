package com.mobileharness.code.browser

/**
 * A single browser tab with its navigation history and extracted content.
 */
data class BrowserTab(
    val id: String,
    var currentUrl: String = "",
    var title: String = "",
    var content: String = "",
    val history: MutableList<String> = mutableListOf(),
    var historyIndex: Int = -1,
) {
    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex >= 0 && historyIndex < history.size - 1
}

/**
 * A pure, JVM-testable browser session model: tab management, navigation history (back/forward),
 * and bounded content extraction. It is intentionally free of Android/WebView so the agent-facing
 * semantics (tabs, history, content) can be reasoned about and tested without a device. The actual
 * WebView rendering/JS layer lives behind an interface and is wired separately.
 *
 * Web content is treated as untrusted input at every boundary by [BrowserGuard]; this model never
 * executes scripts and never stores credentials.
 */
class BrowserSession(private val maxTabs: Int = 12, private val maxContentChars: Int = 200_000) {
    private val tabs = LinkedHashMap<String, BrowserTab>()
    private var nextId = 1

    val activeTabId: String? get() = tabs.values.firstOrNull()?.id
    fun tabs(): List<BrowserTab> = tabs.values.toList()

    fun openTab(initialUrl: String = ""): BrowserTab {
        val id = "tab-${nextId++}"
        val tab = BrowserTab(id = id)
        tabs[id] = tab
        if (initialUrl.isNotBlank()) navigate(initialUrl, tabId = id)
        return tab
    }

    fun closeTab(id: String): Boolean = tabs.remove(id) != null

    fun activateTab(id: String): Boolean = tabs.containsKey(id)

    fun tab(id: String): BrowserTab? = tabs[id]

    /**
     * Navigates the active tab (or the tab with [tabId]) to [url], pushing onto the navigation
     * history. URLs are validated by [BrowserGuard]; a blocked URL is not navigated.
     */
    fun navigate(url: String, tabId: String? = null): Boolean {
        if (!BrowserGuard.isAllowed(url)) return false
        val tab = tabId?.let { tabs[it] } ?: tabs.values.firstOrNull() ?: return false
        // Truncate forward history, then append.
        if (tab.historyIndex >= 0 && tab.historyIndex < tab.history.size - 1) {
            while (tab.history.size - 1 > tab.historyIndex) tab.history.removeAt(tab.history.size - 1)
        }
        tab.history.add(url)
        tab.historyIndex = tab.history.size - 1
        tab.currentUrl = url
        return true
    }

    fun back(tabId: String? = null): String? {
        val tab = tabId?.let { tabs[it] } ?: tabs.values.firstOrNull() ?: return null
        if (!tab.canGoBack) return null
        tab.historyIndex--
        tab.currentUrl = tab.history[tab.historyIndex]
        return tab.currentUrl
    }

    fun forward(tabId: String? = null): String? {
        val tab = tabId?.let { tabs[it] } ?: tabs.values.firstOrNull() ?: return null
        if (!tab.canGoForward) return null
        tab.historyIndex++
        tab.currentUrl = tab.history[tab.historyIndex]
        return tab.currentUrl
    }

    /** Records extracted page content (bounded) against a tab. */
    fun setContent(id: String, title: String, content: String) {
        val tab = tabs[id] ?: return
        tab.title = title.take(500)
        tab.content = content.take(maxContentChars)
    }

    fun size(): Int = tabs.size
}
