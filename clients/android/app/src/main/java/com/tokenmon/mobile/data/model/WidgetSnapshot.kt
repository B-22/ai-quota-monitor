package com.tokenmon.mobile.data.model

import com.tokenmon.mobile.core.LENIENT_JSON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the Home Screen widget renders, and its round-trip through storage.
 *
 * A widget cannot depend on the app process being alive, so it reads a snapshot
 * the app wrote on its last successful fetch. The snapshot is stored **in the Hub
 * wire shape** and decoded with [StatsParser] rather than a second bespoke format
 * — one parser, one schema, and the widget automatically gains whatever field the
 * dashboard already understands.
 *
 * Only `limits` is persisted. Token/session/project accounting stays in the Hub;
 * caching it here would duplicate state that can drift.
 */
data class WidgetSnapshot(
    val capturedAt: java.time.Instant,
    val hubUrl: String,
    val providers: List<LimitProvider>
) {
    fun encode(): String = buildJsonObject {
        put("updatedAt", capturedAt.toString())
        // Carried in the body so a decoded snapshot is self-contained: the widget
        // tells "not configured yet" apart from "Hub returned no quota data" using
        // this, and that distinction is wrong when the field is always empty.
        put("hubUrl", hubUrl)
        put("staleAfterMs", 0)
        put(
            "limits",
            buildJsonObject {
                put("updatedAt", capturedAt.toString())
                put("providers", buildJsonArray { providers.forEach { add(encodeProvider(it)) } })
            }
        )
        put("devices", JsonArray(emptyList()))
        put("subscriptionsUpdatedAt", "")
    }.toString()

    companion object {
        val EMPTY: WidgetSnapshot =
            WidgetSnapshot(java.time.Instant.EPOCH, "", emptyList())

        fun decode(raw: String): WidgetSnapshot? = runCatching {
            val root = LENIENT_JSON.parseToJsonElement(raw) as? JsonObject ?: return null
            val stats = StatsParser.parseStats(root)
            WidgetSnapshot(
                capturedAt = stats.updatedAt ?: stats.limitsUpdatedAt ?: java.time.Instant.EPOCH,
                hubUrl = (root["hubUrl"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                providers = stats.providers
            )
        }.getOrNull()

        private fun encodeProvider(provider: LimitProvider): JsonObject = buildJsonObject {
            put("provider", provider.providerId)
            put("accountKey", provider.accountKey)
            put("accountName", provider.accountName)
            put("accountEmail", provider.accountEmail)
            put("accountLabel", provider.accountLabel)
            put("planLabel", provider.planLabel)
            put("workspaceKind", provider.workspaceKind)
            put("status", provider.status)
            put("source", provider.source)
            put("stale", provider.stale)
            put("sourceDeviceId", provider.sourceDeviceId)
            provider.updatedAt?.let { put("updatedAt", it.toString()) }
            provider.actionRequired?.let { put("actionRequired", it) }
            provider.adapterId?.let { put("adapterId", it) }
            provider.resetCredits?.let { credits ->
                put("resetCredits", buildJsonObject {
                    credits.availableCount?.let { put("availableCount", it) }
                    credits.nextExpiresAt?.let { put("nextExpiresAt", it.toString()) }
                    put("expirations", buildJsonArray {
                        credits.expirations.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) }
                    })
                })
            }
            val amount = provider.balanceAmount
            if (amount != null) {
                put("balanceUsd", amount)
                put("balance", buildJsonObject {
                    put("amount", amount)
                    provider.balanceCurrency?.let { currency -> put("currency", currency) }
                })
            }
            put("windows", buildJsonArray { provider.windows.forEach { add(encodeWindow(it)) } })
        }

        private fun encodeWindow(window: LimitWindow): JsonObject = buildJsonObject {
            put("kind", window.rawKind)
            put("label", window.label)
            put("showMeter", window.showMeter)
            put("additional", window.additional)
            window.metric?.let { put("metric", it) }
            window.usedPercent?.let { put("usedPercent", it) }
            window.used?.let { put("used", it) }
            window.limit?.let { put("limit", it) }
            window.remaining?.let { put("remaining", it) }
            window.currency?.let { put("currency", it) }
            window.boundaryKind?.let { put("boundaryKind", it) }
            window.limitId?.let { put("limitId", it) }
            window.detail?.let { put("detail", it) }
            window.windowMinutes?.let { put("windowMinutes", it) }
            put("resetsAt", window.resetsAtRaw)
            if (window.resetDescription.isNotEmpty()) put("resetDescription", window.resetDescription)
        }
    }
}

/** Validates that [raw] parses; used by the widget's defensive read path. */
fun isDecodableSnapshot(raw: String?): Boolean =
    raw != null && runCatching { StatsParser.parseStats(raw) }.isSuccess
