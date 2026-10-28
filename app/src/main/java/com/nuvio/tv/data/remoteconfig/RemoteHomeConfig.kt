package com.nuvio.tv.data.remoteconfig

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One row of a tab: an MDBList list shown under [title], items ordered by [sort]. */
data class RemoteRow(
    val list: String,
    val title: String,
    val sort: String = "added",
    val limit: Int = 30,
    /** "mdblist" rows read a precomputed file; "addon" rows load a catalog from an installed addon. */
    val source: String = SOURCE_MDBLIST,
    val file: String? = null,
    val catalogId: String? = null,
    val type: String? = null,
    /** "top10" turns the row into the Netflix numbered Top 10 template. */
    val style: String? = null,
    /** Show a different random pick of the stored pool each time the app opens. */
    val shuffle: Boolean = false
) {
    val isTop10: Boolean get() = style.equals(STYLE_TOP10, ignoreCase = true)

    companion object {
        const val STYLE_TOP10 = "top10"
        const val SOURCE_MDBLIST = "mdblist"
        const val SOURCE_ADDON = "addon"
    }
}

data class RemoteHomeConfig(
    val home: List<RemoteRow> = emptyList(),
    val movies: List<RemoteRow> = emptyList(),
    val series: List<RemoteRow> = emptyList()
) {
    fun rowsFor(tab: String): List<RemoteRow> = when (tab) {
        TAB_HOME -> home
        TAB_MOVIES -> movies
        TAB_SERIES -> series
        else -> emptyList()
    }

    companion object {
        const val TAB_HOME = "home"
        const val TAB_MOVIES = "movies"
        const val TAB_SERIES = "series"
    }
}

/**
 * Downloads the tab/row layout from [BuildConfig.REMOTE_CONFIG_URL] on every call, keeping the
 * last good copy on disk so the app still works offline.
 */
@Singleton
class RemoteHomeConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val cacheFile get() = File(context.filesDir, "remote_home_config.json")

    @Volatile
    private var memory: RemoteHomeConfig? = null

    /** Last known config without touching the network (memory, then disk); null on first run. */
    suspend fun cached(): RemoteHomeConfig? = memory ?: withContext(Dispatchers.IO) {
        runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()?.let(::parse)
            ?.also { memory = it }
    }

    suspend fun load(): RemoteHomeConfig = withContext(Dispatchers.IO) {
        val remote = runCatching { fetchRemote() }
            .onFailure { Log.w(TAG, "Remote config fetch failed: ${it.message}") }
            .getOrNull()
        if (remote != null) {
            runCatching { cacheFile.writeText(remote) }
            parse(remote)?.let { memory = it; return@withContext it }
        }
        runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()
            ?.let(::parse) ?: RemoteHomeConfig()
    }

    private fun cacheBusted(url: String): String =
        url + (if ('?' in url) "&" else "?") + "t=" + (System.currentTimeMillis() / 60_000L)

    private fun fetchRemote(): String? {
        val url = BuildConfig.REMOTE_CONFIG_URL
        if (url.isBlank()) return null
        // raw.githubusercontent.com caches ~5 min per URL; a per-minute query value skips stale copies.
        val request = Request.Builder().url(cacheBusted(url)).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun parse(text: String): RemoteHomeConfig? = runCatching {
        val tabs = JSONObject(text).getJSONObject("tabs")
        fun rows(name: String): List<RemoteRow> {
            val array = tabs.optJSONArray(name) ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val source = o.optString("source").ifBlank { RemoteRow.SOURCE_MDBLIST }
                    val title = o.optString("title")
                    if (source == RemoteRow.SOURCE_ADDON) {
                        val catalogId = o.optString("catalogId").trim()
                        if (catalogId.isEmpty()) continue
                        add(
                            RemoteRow(
                                list = "addon:$catalogId",
                                title = title.ifBlank { catalogId },
                                source = source,
                                catalogId = catalogId,
                                type = o.optString("type").ifBlank { "movie" },
                                style = o.optString("style").ifBlank { null }
                            )
                        )
                    } else {
                        val list = o.optString("list").trim()
                        val file = o.optString("file").ifBlank { null }
                        if (list.isEmpty() || file == null) continue
                        add(
                            RemoteRow(
                                list = list,
                                title = title.ifBlank { list },
                                sort = o.optString("sort").ifBlank { "added" },
                                limit = o.optInt("limit", 30).coerceIn(1, 100),
                                file = file,
                                style = o.optString("style").ifBlank { null },
                                shuffle = o.optBoolean("shuffle", false)
                            )
                        )
                    }
                }
            }
        }
        RemoteHomeConfig(
            home = rows(RemoteHomeConfig.TAB_HOME),
            movies = rows(RemoteHomeConfig.TAB_MOVIES),
            series = rows(RemoteHomeConfig.TAB_SERIES)
        )
    }.onFailure { Log.w(TAG, "Remote config parse failed: ${it.message}") }.getOrNull()

    private companion object {
        const val TAG = "RemoteHomeConfig"
    }
}
