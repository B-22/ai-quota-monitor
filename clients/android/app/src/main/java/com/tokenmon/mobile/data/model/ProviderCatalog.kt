package com.tokenmon.mobile.data.model

/**
 * Cosmetic names for providers and quota windows.
 *
 * This is **display only**. Nothing here decides what the app can show: the Hub
 * is the single source of truth for which providers exist, and any id missing
 * from these tables still renders through [displayName]'s fallback. That is
 * deliberate — upstream adds providers regularly (the catalog went from 4 to 29
 * during 2026), and a viewer that had to be taught every new id would need a
 * release each time. A wrong or missing label here costs cosmetics; nothing else.
 *
 * The window-kind table follows `src/shared/limits/windowLabels.js`: the wire
 * `label` always wins, and only a window that declared nothing but its kind is
 * named here.
 */
object ProviderCatalog {

    /** Vendors that publish their rolling window as "5-hour" rather than "Session". */
    private val FIVE_HOUR_WINDOW_PROVIDERS = setOf(
        "alibaba", "antigravity", "cline", "commandcode",
        "kimi", "volcengine", "zai", "zaiteam"
    )

    private val WINDOW_KIND_LABELS = mapOf(
        WindowKind.DAILY to "每日",
        WindowKind.WEEKLY to "每周",
        WindowKind.BILLING to "每月"
    )

    private val LABELS: Map<String, String> = mapOf(
        "claude" to "Claude",
        "codex" to "Codex",
        "opencode" to "OpenCode",
        "cursor" to "Cursor",
        "antigravity" to "Antigravity",
        "cline" to "Cline",
        "factory" to "Factory Droid",
        "kimi" to "Kimi",
        "grok" to "Grok",
        "copilot" to "GitHub Copilot",
        "zed" to "Zed",
        "commandcode" to "Command Code",
        "mimo" to "Xiaomi MiMo",
        "zai" to "GLM",
        "zaiteam" to "GLM Team",
        "kiro" to "Kiro",
        "workbuddy" to "WorkBuddy",
        "qoder" to "Qoder",
        "deepseek" to "DeepSeek",
        "devin" to "Devin",
        "typesafe" to "TypeSafe",
        "openrouter" to "OpenRouter",
        "minimax" to "minimax",
        "volcengine" to "Volcengine",
        "ollama" to "Ollama",
        "trae" to "Trae CN",
        "alibaba" to "Alibaba Cloud",
        "stepfun" to "StepFun",
        "thirdparty" to "Third-party APIs"
    )

    /** Status wording for `limits.providers[].status` (Hub's closed enum). */
    private val STATUS_LABELS: Map<String, String> = mapOf(
        "ok" to "",
        "disabled" to "已停用",
        "notConfigured" to "未配置",
        "unauthorized" to "需重新登录",
        "rateLimited" to "已限流",
        "sourceRateLimited" to "数据源限流",
        "unavailable" to "不可用",
        "error" to "异常"
    )

    fun displayName(providerId: String): String {
        val key = providerId.trim().lowercase()
        if (key.isEmpty()) return "未知 Provider"
        LABELS[key]?.let { return it }
        // Unknown provider: prettify the id rather than hiding it, so an id the
        // Hub introduced after this build still reads sensibly.
        return key.split('-', '_', '.')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
            .ifEmpty { providerId }
    }

    fun windowLabel(providerId: String, wireLabel: String, kind: WindowKind): String {
        val explicit = wireLabel.trim()
        if (explicit.isNotEmpty()) return explicit
        return windowKindLabel(providerId, kind)
    }

    fun windowKindLabel(providerId: String, kind: WindowKind): String = when (kind) {
        WindowKind.SESSION ->
            if (providerId.trim().lowercase() in FIVE_HOUR_WINDOW_PROVIDERS) "5 小时" else "会话"
        WindowKind.DAILY -> WINDOW_KIND_LABELS.getValue(WindowKind.DAILY)
        WindowKind.WEEKLY -> WINDOW_KIND_LABELS.getValue(WindowKind.WEEKLY)
        WindowKind.BILLING -> WINDOW_KIND_LABELS.getValue(WindowKind.BILLING)
        WindowKind.UNKNOWN -> "额度"
    }

    /** Empty string for `ok`, so a healthy row shows no badge at all. */
    fun statusLabel(status: String): String = STATUS_LABELS[status.trim()] ?: ""
}
