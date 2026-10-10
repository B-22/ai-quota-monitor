package com.tokenmon.mobile.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tokenmon.mobile.TokenMonitorApp
import com.tokenmon.mobile.data.model.LimitProvider
import com.tokenmon.mobile.data.model.LimitWindow
import com.tokenmon.mobile.data.model.WidgetAppearance
import com.tokenmon.mobile.data.model.WidgetSnapshot
import com.tokenmon.mobile.ui.Format
import java.time.Instant

/**
 * 多账号小组件：把快照里**所有**账号和到期日期列出来。
 *
 * 单账号小组件按设计稿只画一个账号，多出来的账号和每条到期日期就没地方去了；这个
 * 组件就是那个去处。它同样是快照的只读视图，不发请求。
 */
class MultiQuotaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as TokenMonitorApp).container
        val snapshot = runCatching { container.widgetCache.read() }.getOrDefault(WidgetSnapshot.EMPTY)
        val appearance = runCatching { container.settingsStore.widgetAppearance() }
            .getOrDefault(WidgetAppearance.SYSTEM)
        provideContent { MultiWidgetBody(snapshot, appearance, openAppIntent(context)) }
    }
}

class MultiQuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MultiQuotaWidget()
}

@Composable
private fun MultiWidgetBody(snapshot: WidgetSnapshot, appearance: WidgetAppearance, onOpen: Intent) {
    val ink = widgetInk(appearance)
    // 底板画在 padding 之前：否则只有文字那一块有底，四边会露出壁纸。
    var base = GlanceModifier.fillMaxSize()
    ink.scrim?.let { scrim ->
        base = base.background(ColorProvider(scrim)).cornerRadius(WIDGET_CORNER_RADIUS_DP.dp)
    }
    val surface = base.padding(8.dp).clickable(actionStartActivity(onOpen))
    Column(modifier = surface) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                "AI Quota",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(ink.primary)
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            SecondaryText(updatedLabel(snapshot, Instant.now()), ink)
        }
        Spacer(GlanceModifier.height(4.dp))
        val providers = snapshot.providers.filter { it.hasContent || it.parseIssue != null }
        if (providers.isEmpty()) {
            SecondaryText(emptyLabel(snapshot), ink, maxLines = 2)
        } else {
            // One entry per reset/expiry line lets every date and provider be reached,
            // rather than making a single oversized RemoteViews item that is clipped.
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                providers.forEach { provider ->
                    item { ProviderHeading(provider, ink) }
                    if (provider.parseIssue != null) {
                        item { SecondaryText("额度解析失败，请打开 App", ink) }
                    } else {
                        provider.resetCredits?.let { credits ->
                            item { SecondaryText(creditsCountLabel(credits), ink) }
                            item { SecondaryText(nearestCreditExpiryLabel(credits, Instant.now()), ink, maxLines = 2) }
                        }
                        provider.windows.chunked(2).forEach { pair ->
                            item {
                                Row(modifier = GlanceModifier.fillMaxWidth()) {
                                    pair.forEach { window ->
                                        Column(
                                            modifier = GlanceModifier.defaultWeight().padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.Horizontal.CenterHorizontally
                                        ) {
                                            WindowMeter(window, 56.dp, provider.displayName, ink)
                                            SecondaryText(windowLabel(window), ink)
                                        }
                                    }
                                }
                            }
                            pair.forEach { window ->
                                item {
                                    SecondaryText(
                                        "${windowLabel(window)} · ${windowResetLabel(window, Instant.now(), full = true)}",
                                        ink,
                                        maxLines = 3
                                    )
                                }
                                if (window.detail.isNotEmpty()) {
                                    item { SecondaryText(window.detail, ink, maxLines = 3) }
                                }
                            }
                        }
                        if (provider.balanceAmount != null) {
                            item {
                                SecondaryText(
                                    "余额 ${Format.money(provider.balanceAmount, provider.balanceCurrency)}",
                                    ink
                                )
                            }
                        }
                        provider.resetCredits?.let { credits ->
                            creditExpiryLines(credits, Instant.now()).forEach { line ->
                                item { SecondaryText(line, ink, maxLines = 3) }
                            }
                        }
                    }
                    item { Spacer(GlanceModifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun WindowMeter(window: LimitWindow, diameter: Dp, providerName: String, ink: WidgetSurface) {
    if (window.showMeter && !window.isMoneyWindow) {
        QuotaRing(window.remainingPercent, diameter, "$providerName ${windowLabel(window)}", ink.dark)
    } else {
        Text(
            Format.headline(window),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ColorProvider(ink.primary)),
            maxLines = 2
        )
    }
}

@Composable
private fun ProviderHeading(provider: LimitProvider, ink: WidgetSurface) {
    Text(
        provider.displayName + if (provider.stale) " · 已过期" else "",
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ColorProvider(ink.primary)),
        maxLines = 1
    )
}
