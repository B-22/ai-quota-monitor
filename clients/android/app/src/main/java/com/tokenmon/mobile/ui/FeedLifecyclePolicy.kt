package com.tokenmon.mobile.ui

import com.tokenmon.mobile.data.HubConfig

/** A revision detects credential replacement without retaining plaintext. */
internal data class FeedIdentity(val hubUrl: String, val hasSecret: Boolean, val secretRevision: Long)

internal data class FeedPlan(val restart: Boolean, val stop: Boolean, val clearData: Boolean)

internal class FeedLifecyclePolicy {
    private var selected: FeedIdentity? = null
    private var observing: FeedIdentity? = null

    fun reconcile(config: HubConfig, foreground: Boolean, secretRevision: Long, feedActive: Boolean): FeedPlan {
        val identity = identity(config, secretRevision)
        val changed = selected != null && selected != identity
        val shouldObserve = foreground && config.autoRefresh && config.isConfigured
        val restart = shouldObserve && (observing != identity || !feedActive)
        selected = identity
        observing = identity.takeIf { shouldObserve }
        return FeedPlan(restart, !shouldObserve && feedActive, changed)
    }

    companion object {
        fun identity(config: HubConfig, revision: Long) = FeedIdentity(config.hubUrl, config.hasSecret, revision)
    }
}
