package com.github.audioplayer

import org.junit.Assert.*
import org.junit.Test

class AudioProbeTest {
    // --- parseJson テスト ---

    @Test
    fun `parseJson extracts metadata from ffprobe JSON`() {
        val json =
            """
            {
                "streams": [{
                    "codec_name": "mp3",
                    "sample_fmt": "fltp",
                    "channels": 2,
                    "channel_layout": "stereo",
                    "sample_rate": "44100",
                    "duration": "180.5"
                }],
                "format": {
                    "duration": "180.5"
                }
            }
            """.trimIndent()

        val metadata = AudioProbe.parseJson(json, 5_000_000)

        assertNotNull(metadata)
        assertEquals("mp3", metadata!!.encoding)
        assertEquals("fltp", metadata.format)
        assertEquals(2, metadata.channels)
        assertEquals("stereo", metadata.channelLayout)
        assertEquals(44100, metadata.sampleRate)
        assertEquals(5_000_000, metadata.fileSize)
        assertEquals(180.5, metadata.durationSeconds, 0.01)
    }

    @Test
    fun `parseJson handles missing channel_layout for mono`() {
        val json =
            """
            {
                "streams": [{
                    "codec_name": "pcm_s16le",
                    "sample_fmt": "s16",
                    "channels": 1,
                    "sample_rate": "48000",
                    "duration": "60.0"
                }]
            }
            """.trimIndent()

        val metadata = AudioProbe.parseJson(json, 1_000_000)

        assertNotNull(metadata)
        assertEquals(1, metadata!!.channels)
        assertEquals("mono", metadata.channelLayout)
    }

    @Test
    fun `parseJson handles missing channel_layout for stereo`() {
        val json =
            """
            {
                "streams": [{
                    "codec_name": "aac",
                    "sample_fmt": "fltp",
                    "channels": 2,
                    "sample_rate": "44100",
                    "duration": "30.0"
                }]
            }
            """.trimIndent()

        val metadata = AudioProbe.parseJson(json, 500_000)

        assertNotNull(metadata)
        assertEquals("stereo", metadata!!.channelLayout)
    }

    @Test
    fun `parseJson handles multi-channel layout`() {
        val json =
            """
            {
                "streams": [{
                    "codec_name": "aac",
                    "sample_fmt": "fltp",
                    "channels": 6,
                    "sample_rate": "48000",
                    "duration": "120.0"
                }]
            }
            """.trimIndent()

        val metadata = AudioProbe.parseJson(json, 10_000_000)

        assertNotNull(metadata)
        assertEquals("6ch", metadata!!.channelLayout)
    }

    // --- formatFileSize テスト ---

    @Test
    fun `formatFileSize formats bytes`() {
        assertEquals("500 bytes", AudioProbe.formatFileSize(500))
    }

    @Test
    fun `formatFileSize formats kilobytes`() {
        assertEquals("1.5 KB", AudioProbe.formatFileSize(1_500))
    }

    @Test
    fun `formatFileSize formats megabytes`() {
        assertEquals("5.0 MB", AudioProbe.formatFileSize(5_000_000))
    }

    @Test
    fun `formatFileSize formats gigabytes`() {
        assertEquals("2.0 GB", AudioProbe.formatFileSize(2_000_000_000))
    }

    // --- formatSampleRate テスト ---

    @Test
    fun `formatSampleRate formats 44100 Hz`() {
        assertEquals("44,100 Hz", AudioProbe.formatSampleRate(44100))
    }

    @Test
    fun `formatSampleRate formats 48000 Hz`() {
        assertEquals("48,000 Hz", AudioProbe.formatSampleRate(48000))
    }

    // --- formatChannels テスト ---

    @Test
    fun `formatChannels formats mono`() {
        assertEquals("1 ch (mono)", AudioProbe.formatChannels(1, "mono"))
    }

    @Test
    fun `formatChannels formats stereo`() {
        assertEquals("2 ch (stereo)", AudioProbe.formatChannels(2, "stereo"))
    }

    @Test
    fun `parseTags extracts format tags lowercased`() {
        val json =
            """
            {
                "format": {
                    "tags": { "title": "Song", "ARTIST": "Band", "album": "Disc" }
                }
            }
            """.trimIndent()
        val tags = AudioProbe.parseTags(json)
        assertEquals("Song", tags["title"])
        assertEquals("Band", tags["artist"])
        assertEquals("Disc", tags["album"])
    }

    @Test
    fun `parseTags returns empty when no tags`() {
        assertTrue(AudioProbe.parseTags("""{ "format": {} }""").isEmpty())
    }
}
