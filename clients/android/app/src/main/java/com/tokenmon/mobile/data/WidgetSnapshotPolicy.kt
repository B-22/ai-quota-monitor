package com.tokenmon.mobile.data

/** Capture under the shared connection lock, then fetch outside that lock. */
internal data class WidgetSnapshotRequest(val config: HubConfig, val revision: Long)

/** No credentials or Android dependencies: checks the scope of cached data. */
internal object WidgetSnapshotPolicy {
    fun canPublish(
        request: WidgetSnapshotRequest,
        currentConfig: HubConfig,
        currentRevision: Long,
        snapshotHubUrl: String
    ): Boolean = currentConfig.isConfigured && request.config == currentConfig &&
        request.revision == currentRevision && snapshotHubUrl == currentConfig.hubUrl

    fun cacheMatches(config: HubConfig, snapshotHubUrl: String): Boolean =
        config.isConfigured && snapshotHubUrl == config.hubUrl
}
