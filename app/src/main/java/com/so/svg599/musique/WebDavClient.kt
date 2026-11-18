package com.so.svg599.musique

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV credentials stay in memory only in this first integration. */
object WebDavAuth {
    @Volatile private var header: String? = null
    @Volatile var baseUrl: String = "https://ewebdav.pcloud.com"
        private set
    @Volatile var username: String = ""
        private set

    fun configure(server: String, user: String, password: String) {
        baseUrl = server.trim().trimEnd('/')
        username = user.trim()
        header = "Basic " + Base64.encodeToString("${username}:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
    fun clear() { header = null; username = "" }
    fun authorizationHeader(): String? = header
    fun isConfigured(): Boolean = !header.isNullOrBlank()
}

data class WebDavEntry(val name: String, val url: String, val isDirectory: Boolean, val size: Long = 0L)

object WebDavClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            WebDavAuth.authorizationHeader()?.let { builder.header("Authorization", it) }
            chain.proceed(builder.build())
        }.build()

    fun list(url: String): List<WebDavEntry> {
        val xmlBody = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        val body = xmlBody.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw IllegalStateException("Identifiants refusés (HTTP ${response.code}). Vérifie le serveur, l'e-mail et le mot de passe pCloud.")
            if (!response.isSuccessful && response.code != 207) throw IllegalStateException("Erreur WebDAV HTTP ${response.code} ${response.message}")
            val xml = response.body?.string() ?: throw IllegalStateException("Réponse WebDAV vide")
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val entries = mutableListOf<WebDavEntry>()
            for (i in 0 until responses.length) {
                val el = responses.item(i) as? Element ?: continue
                val href = childText(el, "href") ?: continue
                val resolved = URI(url).resolve(href).toString()
                if (sameResource(resolved, url)) continue
                val resourceType = firstDescendant(el, "resourcetype")
                val isDir = resourceType?.getElementsByTagNameNS("DAV:", "collection")?.length?.let { it > 0 } ?: false
                val display = childText(el, "displayname")?.takeIf { it.isNotBlank() }
                    ?: URI(resolved).path.trimEnd('/').substringAfterLast('/').ifBlank { "Sans nom" }
                val size = childText(el, "getcontentlength")?.toLongOrNull() ?: 0L
                entries += WebDavEntry(display, resolved, isDir, size)
            }
            return entries.sortedWith(compareByDescending<WebDavEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    private fun sameResource(a: String, b: String): Boolean = a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)
    private fun childText(parent: Element, local: String): String? = firstDescendant(parent, local)?.textContent?.trim()
    private fun firstDescendant(parent: Element, local: String): Element? = parent.getElementsByTagNameNS("DAV:", local).item(0) as? Element
}
