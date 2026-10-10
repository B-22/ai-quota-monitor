package com.tokenmon.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tokenmon.mobile.data.ProbeResult
import com.tokenmon.mobile.data.model.WidgetAppearance

/**
 * Settings: Hub URL, Hub Secret, and a connection test.
 *
 * The secret field is a normal Compose text field bound to local state and never
 * to the keystore until the user taps save. It is masked by default, excluded
 * from autofill/suggestions, and never logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onSaveUrl: (String) -> Unit,
    onSaveSecret: (String) -> Unit,
    onClearSecret: () -> Unit,
    onSetAutoRefresh: (Boolean) -> Unit,
    onSetWidgetAppearance: (WidgetAppearance) -> Unit,
    onTestConnection: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var url by remember(state.config.hubUrl) { mutableStateOf(state.config.hubUrl) }
    var secret by remember { mutableStateOf("") }
    var secretVisible by remember { mutableStateOf(false) }

    val cleartextWarning = url.trim().startsWith("http://")

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Text("连接 Hub", style = MaterialTheme.typography.titleMedium)
            Text("Hub 地址", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://token.example.com") },
                supportingText = {
                    Text("支持 HTTPS、局域网或 Tailscale 地址")
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                )
            )

            if (cleartextWarning) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "当前连接未加密，仅建议用于可信局域网或 Tailscale 网络。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Text("Hub Secret", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入 Hub Secret") },
                supportingText = { Text(if (state.config.hasSecret) "已保存，留空保持现有 Secret" else "填写与 Hub 配置一致的 Secret") },
                visualTransformation = if (secretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    IconButton(onClick = { secretVisible = !secretVisible }) {
                        Icon(
                            imageVector = if (secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (secretVisible) "隐藏" else "显示"
                        )
                    }
                }
            )

            Text(
                text = "Secret 加密保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.secretNeedsReentry) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "已保存的 Secret 无法解密，请重新输入。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            SettingsActions(
                isTesting = state.isTesting,
                canTest = url.isNotBlank() && (secret.isNotBlank() || state.config.hasSecret),
                onSave = {
                    onSaveUrl(url)
                    if (secret.isNotBlank()) onSaveSecret(secret)
                },
                onTest = { onTestConnection(url, secret.ifBlank { EXISTING_SECRET }) }
            )

            if (state.config.hasSecret) {
                OutlinedButton(
                    onClick = { onClearSecret(); secret = "" },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清除已保存的 Secret") }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动实时刷新", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "打开 App 时自动更新；桌面小组件由后台定时更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.config.autoRefresh,
                    onCheckedChange = onSetAutoRefresh
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text("小组件背景", style = MaterialTheme.typography.titleMedium)
            WidgetAppearancePicker(
                selected = state.config.widgetAppearance,
                onSelect = onSetWidgetAppearance
            )

            state.probe?.let { probe ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("连接测试结果", style = MaterialTheme.typography.titleSmall)
                ProbeResultCard(probe)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Single-choice list for [WidgetAppearance].
 *
 * Four stacked radio rows rather than a segmented row: the labels are three or
 * four CJK characters each and the screen already has to survive 2x font scale,
 * where a segmented row overflows. The hint under the group explains only the
 * current choice, so the group stays one screen tall at large font sizes.
 */
@Composable
private fun WidgetAppearancePicker(
    selected: WidgetAppearance,
    onSelect: (WidgetAppearance) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        WidgetAppearance.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.RadioButton,
                        onClickLabel = "选择${option.label}背景"
                    ) { onSelect(option) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = option == selected,
                    // Null keeps the row click the only toggle, so a tap never
                    // fires both the row and the button.
                    onClick = null
                )
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        Text(
            text = selected.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * The connection test result, in the terms the user can act on.
 *
 * Every branch says what happened *and* what to do, because "连接失败" alone
 * cannot distinguish a wrong secret from a Hub that is not running.
 */
@Composable
private fun ProbeResultCard(result: ProbeResult) {
    val (title, tone, lines) = describeProbe(result)

    var expanded by rememberSaveable(result) { mutableStateOf(false) }
    Surface(
        color = toneBackground(tone), contentColor = toneForeground(tone),
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (result) {
                    is ProbeResult.Ok -> "已连接 Hub，额度数据可读取。"
                    is ProbeResult.Unauthorized -> "请检查填写的 Secret 是否与 Hub 配置一致。"
                    is ProbeResult.Unreachable -> "请检查网络与 Hub 地址。"
                    is ProbeResult.InvalidUrl -> "请输入有效的 Hub 地址。"
                    is ProbeResult.NotConfigured -> "请先填写 Hub 地址与 Secret。"
                    is ProbeResult.Incompatible -> "Hub 响应无法识别，请检查地址与版本。"
                    is ProbeResult.HttpError -> "Hub 返回 HTTP ${result.httpCode}，请稍后重试。"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "收起测试详情" else "展开测试详情") { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("详细结果", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (expanded) lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun SettingsActions(isTesting: Boolean, canTest: Boolean, onSave: () -> Unit, onTest: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val saveButton: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = onSave, modifier = modifier) { Text("保存") }
        }
        val testButton: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedButton(onClick = onTest, enabled = !isTesting && canTest, modifier = modifier) {
                if (isTesting) {
                    CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("测试连接")
            }
        }
        if (maxWidth < (280f * fontScale).dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                saveButton(Modifier.fillMaxWidth())
                testButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                saveButton(Modifier.weight(1f))
                testButton(Modifier.weight(1f))
            }
        }
    }
}

private fun describeProbe(result: ProbeResult): Triple<String, ChipTone, List<String>> = when (result) {
    is ProbeResult.NotConfigured -> Triple(
        "尚未填写完整",
        ChipTone.Neutral,
        listOf("Hub 地址与 Secret 都需要填写后才能测试。")
    )

    is ProbeResult.InvalidUrl -> Triple(
        "地址无效",
        ChipTone.Critical,
        listOf(result.message, "示例：https://token.example.com 或 http://100.101.102.103:17321")
    )

    is ProbeResult.Ok -> {
        val health = result.health
        val lines = mutableListOf(
            "GET /api/health → HTTP 200",
            "GET /api/stats → HTTP 200，Secret 通过",
            "Hub 运行时：${health.runtime.ifEmpty { "未知" }}，设备数：${health.deviceCount}",
            "额度 Provider 数：${result.stats.providers.size}"
        )
        health.hubBuild?.coreBuildId?.takeIf { it.isNotEmpty() }?.let { lines += "Hub 构建标识：$it" }
        result.warning?.let { lines += "注意：$it" }
        if (result.stats.unreadableProviders.isNotEmpty()) {
            lines += "无法解析：${result.stats.unreadableProviders.joinToString { it.providerId }}"
        }
        Triple("连接成功", ChipTone.Positive, lines)
    }

    is ProbeResult.Unauthorized -> Triple(
        "Secret 错误",
        ChipTone.Critical,
        buildList {
            add("Hub 返回 HTTP ${result.httpCode}，说明 Authorization: Bearer 中的 Secret 与 Hub 的 TOKEN_MONITOR_SECRET 不一致。")
            add("检查 Hub 上的 TOKEN_MONITOR_SECRET，或本 App 设置页填写的值。")
            result.hubRuntime?.let { add("Hub 运行时：$it") }
        }
    )

    is ProbeResult.Incompatible -> Triple(
        "数据格式不兼容",
        ChipTone.Critical,
        listOf(
            "Hub 返回 HTTP ${result.httpCode}，但响应不是本 App 能识别的 Token Monitor 数据。",
            result.message,
            "如果 Hub 在反向代理后面，确认 /api/ 没有被改写。"
        )
    )

    is ProbeResult.HttpError -> Triple(
        "Hub 返回异常状态",
        ChipTone.Warning,
        buildList {
            add("${result.endpoint} → HTTP ${result.httpCode}")
            if (result.body.isNotBlank()) add("响应：${result.body.take(160)}")
        }
    )

    is ProbeResult.Unreachable -> Triple(
        "Hub 不可达",
        ChipTone.Critical,
        buildList {
            add(result.message)
            result.hint?.let { add(it) }
            add("确认手机与 Hub 在同一局域网，或已连上 Tailscale。")
        }
    )
}

/** Sentinel meaning "use the already-stored secret" when testing without retyping. */
const val EXISTING_SECRET = "\u0000use-stored"
