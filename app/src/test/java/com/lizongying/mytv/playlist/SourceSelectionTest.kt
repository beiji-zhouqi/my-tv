package com.lizongying.mytv.playlist

import com.lizongying.mytv.TVList
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceSelectionTest {
    @Test fun prefersYangshipinBeforeHigherResolutionAndPreservesFallbackOrder() {
        val qualities = listOf(
            SourceQualityResolver.Quality(pixels = 3840L * 2160),
            SourceQualityResolver.Quality(pixels = 1280L * 720),
            SourceQualityResolver.Quality(pixels = 1920L * 1080),
        )
        val ranked = SourceQualityResolver.ranking(qualities)
        assertEquals(listOf(1, 0, 2), SourceQualityResolver.preferYangshipin(
            ranked, listOf("咪咕", "央视频", "电信")).order)
        assertEquals(ranked.order, SourceQualityResolver.preferYangshipin(ranked, emptyList()).order)
        val failed = SourceQualityResolver.preferYangshipin(
            SourceQualityResolver.ranking(listOf(qualities[0], qualities[1].copy(failed = true))),
            listOf("咪咕", "央视频"))
        assertEquals(0, failed.order.first { !failed.qualities[it].failed })
    }

    @Test fun fallbackStopsAfterThreeFailuresAndSkipsCoolingSources() {
        val failures = SourceFailures()
        failures.playbackFailed(0)
        assertEquals(3000L, failures.retryDelayMs())
        assertEquals(2, failures.nextPlayback(listOf(0, 1, 2, 3), 4, setOf(2, 3)))
        failures.playbackFailed(2)
        assertEquals(6000L, failures.retryDelayMs())
        assertEquals(3, failures.nextPlayback(listOf(0, 1, 2, 3), 4, setOf(3)))
        failures.playbackFailed(3)
        assertEquals(null, failures.nextPlayback(listOf(0, 1, 2, 3), 4))
        failures.clear()
        assertEquals(0, failures.nextPlayback(listOf(0), 4))
    }

    @Test fun resolutionSurvivesCooldownWithoutClaimingCurrentAvailability() {
        val failed = SourceQualityResolver.Quality(pixels = 1920L * 1080, width = 1920,
            height = 1080, failed = true, playbackFailed = true)
        assertTrue(SourceQualityResolver.historicalQuality(failed, 599_999).failed)
        val history = SourceQualityResolver.historicalQuality(failed, 600_000)
        assertFalse(history.failed)
        assertFalse(history.playbackFailed)
        assertEquals(1920, history.width)
        assertEquals(1080, history.height)
    }

    @Test fun firstFrameTimingUsesActualPlaybackRatherThanPreviousProbe() {
        assertEquals(650L, SourceQualityResolver.recoveredQuality(
            SourceQualityResolver.Quality(loadMs = 650), SourceQualityResolver.Quality(loadMs = 800)
        ).loadMs)
    }

    @Test fun successfulPlaybackClearsBothFailureFlagsAndKeepsLoadTime() {
        val result = SourceQualityResolver.recoveredQuality(
            SourceQualityResolver.Quality(pixels = 1920L * 1080, width = 1920, height = 1080),
            SourceQualityResolver.Quality(failed = true, playbackFailed = true, loadMs = 800),
        )
        assertFalse(result.failed)
        assertFalse(result.playbackFailed)
        assertEquals(1920, result.width)
        assertEquals(800L, result.loadMs)
    }

    @Test fun sourceLabelsPreferMetadataAndKeepUrlOrder() {
        val groups = TVList.build(listOf(
            M3UChannel("江西卫视", "https://example.com/one", "卫视", "", "", "咪咕"),
            M3UChannel("江西卫视高清", "https://example.com/two", "央视频", "", ""),
            M3UChannel("江西卫视", "https://example.com/one", "江西", "", ""),
        ))
        val channel = groups.getValue("卫视").single()
        assertEquals(listOf("咪咕", "央视频"), channel.sourceNames)
        assertEquals(2, channel.videoUrl.size)
        assertEquals("江西", SourceLabel.name(M3UChannel("江西都市", "http://relay/123", "江西", "", "")))
    }

    @Test fun candidateNeedsFiveSecondsAndSpareBuffer() {
        val gate = UpgradeStability()
        for (time in 0L..4_750L step 250) assertFalse(gate.sample(time, time, true, 3_000))
        assertTrue(gate.sample(5_000, 5_000, true, 3_000))
    }

    @Test fun bufferingAndFrozenVideoResetStability() {
        val gate = UpgradeStability()
        for (time in 0L..4_000L step 250) assertFalse(gate.sample(time, time, true, 3_000))
        assertFalse(gate.sample(4_250, 4_000, true, 3_000))
        assertFalse(gate.sample(4_500, 4_500, false, 3_000))
        for (time in 4_750L..9_500L step 250) assertFalse(gate.sample(time, time, true, 3_000))
        assertTrue(gate.sample(9_750, 9_750, true, 3_000))
    }

    @Test fun insufficientBufferPreventsUpgrade() {
        val gate = UpgradeStability()
        for (time in 0L..6_000L step 250) assertFalse(gate.sample(time, time, true, 1_000))
        assertTrue(gate.sample(6_250, 6_250, true, 2_000))
    }

    @Test fun preservesEveryCategoryWithSharedSources() {
        val sources = listOf(
            M3UChannel("江西卫视", "https://example.com/migu.m3u8", "卫视", "", "江西卫视"),
            M3UChannel("江西卫视", "https://example.com/ysp.m3u8", "央视频", "", "江西卫视"),
            M3UChannel("江西卫视高清", "https://example.com/jx.m3u8", "江西", "", "江西卫视"),
            M3UChannel("江西卫视", "https://example.com/migu.m3u8", "江西", "", "江西卫视"),
            M3UChannel("江西都市", "https://example.com/city.m3u8", "江西", "", "江西都市"),
        )
        val groups = TVList.build(sources)
        assertEquals(listOf("卫视", "央视频", "江西"), groups.keys.toList())
        val satellite = groups.getValue("卫视").single()
        assertSame(satellite, groups.getValue("央视频").single())
        assertSame(satellite, groups.getValue("江西").first())
        assertEquals(3, satellite.videoUrl.size)
        assertEquals(2, groups.getValue("江西").size)
        val unique = groups.values.flatten().distinctBy { it.id }
        assertEquals(listOf(0, 1), unique.map { it.id })
    }

    @Test fun categoryOrderDoesNotDuplicateOrMisindexChannels() {
        val groups = TVList.build(listOf(
            M3UChannel("江西卫视", "https://example.com/1.m3u8", "卫视", "", ""),
            M3UChannel("江西都市", "https://example.com/2.m3u8", "江西", "", ""),
            M3UChannel("湖南卫视", "https://example.com/3.m3u8", "卫视", "", ""),
            M3UChannel("江西卫视", "https://example.com/4.m3u8", "江西", "", ""),
        ))
        val unique = groups.values.flatten().distinctBy { it.id }
        assertEquals(listOf("江西卫视", "湖南卫视", "江西都市"), unique.map { it.title })
        assertEquals(listOf(0, 1, 2), unique.map { it.id })
        assertSame(unique[0], groups.getValue("江西")[1])
    }

    @Test fun aggregatesProvidersAndQualityLabels() {
        listOf("CCTV1综合", "CCTV-1高清（央视频）", "CCTV1-咪咕", "央视1HD", "咪咕CCTV1综合4K")
            .forEach { assertEquals("CCTV1", ChannelIdentity.key(it)) }
    }

    @Test fun preservesDifferentChannels() {
        assertNotEquals(ChannelIdentity.key("CCTV5体育"), ChannelIdentity.key("CCTV5+体育赛事"))
        assertNotEquals(ChannelIdentity.key("北京卫视"), ChannelIdentity.key("北京体育"))
        assertNotEquals(ChannelIdentity.key("CCTV4中文国际"), ChannelIdentity.key("CCTV4K"))
    }

    @Test fun prefersAvailableResolutionThenLoadTime() {
        val qualities = listOf(
            SourceQualityResolver.Quality(3840L * 2160, failed = true, loadMs = 100),
            SourceQualityResolver.Quality(1920L * 1080, bitrate = 9_000_000, loadMs = 1500),
            SourceQualityResolver.Quality(1920L * 1080, bitrate = 2_000_000, loadMs = 500),
            SourceQualityResolver.Quality(1280L * 720, loadMs = 100),
            SourceQualityResolver.Quality(),
        )
        assertEquals(listOf(2, 1, 3, 4, 0), SourceQualityResolver.ranking(qualities).order)
    }

    @Test fun preservesDefaultOrderWhenNoMeasurementsExist() {
        assertEquals(listOf(0, 1, 2), SourceQualityResolver.ranking(
            List(3) { SourceQualityResolver.Quality() }).order)
    }
}
