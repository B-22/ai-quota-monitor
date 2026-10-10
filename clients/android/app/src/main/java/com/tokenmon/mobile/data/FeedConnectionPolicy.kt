package com.tokenmon.mobile.data

/** Receipt times are monotonic client times, independent of Hub data timestamps. */
internal class FeedConnectionPolicy(private val graceMs: Long = 60_000L) {
    var status: ConnectionStatus = ConnectionStatus.Connecting
        private set
    var message: String? = null
        private set
    var streamHealthy: Boolean = false
        private set
    private var lastSuccessAt: Long? = null
    private var lastFailure: String? = null
    private var lastWasPoll = false

    val authFailed: Boolean get() = status == ConnectionStatus.AuthFailed

    fun received(now: Long, fromStream: Boolean) {
        if (authFailed) return
        lastSuccessAt = now
        lastFailure = null
        lastWasPoll = !fromStream
        if (fromStream) streamHealthy = true
        status = if (streamHealthy) ConnectionStatus.Live else ConnectionStatus.Polling
        message = null
    }

    fun connecting(now: Long) {
        if (authFailed) return
        if (lastSuccessAt == null && lastFailure == null) {
            status = ConnectionStatus.Connecting
            message = null
        } else {
            recovering(now)
        }
    }

    fun streamFailed(now: Long, event: StreamEvent.Failed) {
        if (authFailed) return
        streamHealthy = false
        if (isAuthFailure(event.httpCode)) {
            rejectSecret(event.httpCode!!)
        } else {
            lastFailure = event.message
            recovering(now)
        }
    }

    fun pollFailed(now: Long, error: Exception) {
        if (authFailed) return
        if (error is HubRequestException && isAuthFailure(error.httpCode)) {
            rejectSecret(error.httpCode)
            return
        }
        lastFailure = when {
            error is HubRequestException && error.parseFailure != null -> "无法解析 Hub 数据，正在重试"
            error is HubRequestException -> "Hub 返回 HTTP ${error.httpCode}"
            else -> "无法连接 Hub，请检查网络与地址"
        }
        if (recent(now)) {
            // A failed poll must not hide an actively delivering stream, or a
            // successful fallback poll during a brief reconnect.
            if (!streamHealthy) recovering(now)
        } else {
            status = ConnectionStatus.Offline
            message = lastFailure
        }
    }

    fun tick(now: Long) {
        if (!authFailed && lastFailure != null && lastSuccessAt != null && !recent(now)) {
            status = ConnectionStatus.Offline
            message = lastFailure
        }
    }

    private fun recovering(now: Long) {
        when {
            recent(now) -> {
                status = if (lastWasPoll) ConnectionStatus.Polling else ConnectionStatus.Reconnecting
                message = null
            }
            lastSuccessAt != null || status == ConnectionStatus.Offline -> {
                status = ConnectionStatus.Offline
                message = lastFailure ?: "连接已中断，正在重试"
            }
            else -> {
                status = ConnectionStatus.Reconnecting
                message = lastFailure
            }
        }
    }

    private fun recent(now: Long): Boolean = lastSuccessAt?.let { now - it < graceMs } ?: false

    private fun rejectSecret(code: Int) {
        streamHealthy = false
        status = ConnectionStatus.AuthFailed
        message = "Secret 被 Hub 拒绝（HTTP $code）"
    }

    companion object {
        fun isAuthFailure(code: Int?): Boolean = code == 401 || code == 403
    }
}
