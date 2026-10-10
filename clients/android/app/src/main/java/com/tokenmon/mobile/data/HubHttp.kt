package com.tokenmon.mobile.data

import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * One shared OkHttp client for both the request/response calls and the SSE
 * stream.
 *
 * The stream needs its own generous timeouts — `readTimeout(0)` turns off the
 * socket read deadline that a normal call relies on, because an SSE connection
 * is *supposed* to sit idle between events. Those settings are applied to a
 * derived client rather than to the shared one so a stalled Hub cannot also stall
 * a `/api/stats` poll.
 */
object HubHttp {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val streamClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // An SSE connection is idle most of its life. The Hub sends `: hb`
            // every 30s, so a read deadline would cut a healthy stream.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    // Opt in only after an HTTP/2 INTERNAL_ERROR on this feed. The Node Hub
    // speaks HTTP/1.1 natively; this bypasses HTTP/2 stream resets on the client
    // leg without changing REST, authentication, TLS checks or any API contract.
    val http1StreamClient: OkHttpClient by lazy {
        streamClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
}
