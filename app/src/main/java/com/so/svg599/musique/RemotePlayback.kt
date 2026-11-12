package com.so.svg599.musique

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

@androidx.media3.common.util.UnstableApi
object RemotePlayback {
    @Volatile private var diskCache: SimpleCache? = null
    @Synchronized private fun cache(context: Context): SimpleCache {
        return diskCache ?: SimpleCache(File(context.cacheDir, "stream_media_cache"), LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024)).also { diskCache = it }
    }

    private fun site(host: String): String = host.lowercase().split('.').takeLast(2).joinToString(".")

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val client = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val auth = WebDavAuth.authorizationHeader()
                val davHost = runCatching { URI(WebDavAuth.baseUrl).host }.getOrNull()
                // Les identifiants pCloud ne partent que vers pCloud, jamais vers YouTube ou SoundCloud.
                val sendAuth = auth != null && davHost != null && site(request.url.host) == site(davHost)
                chain.proceed(if (sendAuth) request.newBuilder().header("Authorization", auth!!).build() else request)
            }.build()
        val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))
        val cached = CacheDataSource.Factory().setCache(cache(context)).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { RoutingRemoteDataSource(context, ResolvingDataSource.Factory(cached, StreamResolver).createDataSource()) }
    }
}
