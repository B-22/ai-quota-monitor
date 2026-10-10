package com.tokenmon.mobile.widget

import com.tokenmon.mobile.HubFixtures
import com.tokenmon.mobile.data.model.LimitProvider
import com.tokenmon.mobile.data.model.StatsParser
import com.tokenmon.mobile.data.model.WidgetSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * The widget gets a short, unpredictable execution window and is killed right
 * after, so these pure helpers are the whole of its empty/last-updated wording.
 * They are `internal`, which is exactly enough for the unit-test source set.
 *
 * The timezone is pinned in [setUp] because `updatedLabel` renders a wall-clock
 * time; leaving it to the machine would make that one assertion machine-shaped.
 */
class WidgetSelectionTest {

    private lateinit var previousZone: TimeZone

    private val now: Instant = Instant.parse("2026-05-18T04:00:00Z")

    private val parsedProviders: List<LimitProvider> by lazy {
        StatsParser.parseStats(HubFixtures.STATS_BODY).providers
    }

    @Before
    fun setUp() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(previousZone)
    }

    @Test
    fun `updatedLabel reports not-updated for the empty snapshot`() {
        assertEquals("未更新", updatedLabel(WidgetSnapshot.EMPTY, now))
    }

    @Test
    fun `updatedLabel shows the capture clock time`() {
        val snapshot = WidgetSnapshot(Instant.parse("2026-05-18T09:26:00Z"), "https://hub.example.com", emptyList())
        assertEquals("更新 09:26", updatedLabel(snapshot, now))
    }

    @Test
    fun `emptyLabel explains which step is missing`() {
        assertEquals("请先在 App 中配置 Hub", emptyLabel(WidgetSnapshot.EMPTY))
        assertEquals(
            "Hub 暂未返回额度数据",
            emptyLabel(WidgetSnapshot(now, "https://hub.example.com", emptyList()))
        )
        assertEquals(
            "暂无额度窗口",
            emptyLabel(WidgetSnapshot(now, "https://hub.example.com", parsedProviders))
        )
    }

    @Test
    fun `short empty labels stay in step with the full-size ones`() {
        val cases = listOf(
            WidgetSnapshot.EMPTY,
            WidgetSnapshot(now, "https://hub.example.com", emptyList()),
            WidgetSnapshot(now, "https://hub.example.com", parsedProviders)
        )
        // Three distinct snapshots must reach three distinct states, or the two
        // layouts would disagree on whether the app is even configured.
        assertEquals(
            listOf(EmptyState.NO_HUB, EmptyState.NO_DATA, EmptyState.NO_WINDOWS),
            cases.map { emptyState(it) }
        )
        // A one-cell widget fits about three characters.
        assertEquals(listOf("未配置", "无数据", "无额度"), cases.map { shortEmptyLabel(it) })
    }
}
