package com.audiobookshelf.app.player

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds a playback bearer only to the configured server origin.
 *
 * HLS playlists may contain absolute child URLs. Applying Authorization as a
 * data-source default would therefore send the account credential to every
 * origin named by server-controlled playlist content. This interceptor also
 * removes any inherited Authorization header from foreign-origin requests.
 */
internal class OriginBoundBearerInterceptor(
  private val serverOrigin: HttpUrl,
  private val bearerToken: String
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val builder = request.newBuilder()
    if (request.url.sameOriginAs(serverOrigin)) {
      builder.header(AUTHORIZATION, "Bearer $bearerToken")
    } else {
      builder.removeHeader(AUTHORIZATION)
    }
    return chain.proceed(builder.build())
  }

  private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

  private companion object {
    const val AUTHORIZATION = "Authorization"
  }
}
