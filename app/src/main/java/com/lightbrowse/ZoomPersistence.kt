package com.lightbrowse

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Per-domain zoom persistence.
 * Like Chrome: remembers zoom level per website.
 * Global default zoom is also stored.
 */
class ZoomPersistence(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zoom_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_GLOBAL_ZOOM = "global_zoom"
        private const val DEFAULT_ZOOM = 100
    }

    /**
     * Get saved zoom for a specific domain.
     * Falls back to global default if no per-domain setting exists.
     */
    fun getZoomForDomain(url: String?): Int {
        val domain = extractDomain(url) ?: return getGlobalZoom()
        return prefs.getInt(domain, getGlobalZoom())
    }

    /**
     * Save zoom level for a specific domain.
     */
    fun saveZoomForDomain(url: String?, zoomPercent: Int) {
        val domain = extractDomain(url) ?: return
        prefs.edit()
            .putInt(domain, zoomPercent)
            .apply()
    }

    /**
     * Get global default zoom.
     */
    fun getGlobalZoom(): Int {
        return prefs.getInt(KEY_GLOBAL_ZOOM, DEFAULT_ZOOM)
    }

    /**
     * Save global default zoom.
     */
    fun saveGlobalZoom(zoomPercent: Int) {
        prefs.edit()
            .putInt(KEY_GLOBAL_ZOOM, zoomPercent)
            .apply()
    }

    /**
     * Reset zoom for a specific domain.
     */
    fun resetDomainZoom(url: String?) {
        val domain = extractDomain(url) ?: return
        prefs.edit()
            .remove(domain)
            .apply()
    }

    /**
     * Reset all zoom settings.
     */
    fun resetAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Extract domain from URL for per-domain storage.
     * e.g. "https://www.google.com/search?q=test" → "google.com"
     */
    private fun extractDomain(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            // Remove www. prefix for consistent keys
            if (host.startsWith("www.")) host.substring(4) else host
        } catch (e: Exception) {
            null
        }
    }
}
