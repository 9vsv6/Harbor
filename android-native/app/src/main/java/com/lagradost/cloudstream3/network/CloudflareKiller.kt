package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Cloudstream solves Cloudflare challenges in a WebView here. Harbor for Android passes requests
 * through for now, so a site behind a challenge returns nothing rather than a wrong stream.
 */
class CloudflareKiller : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
