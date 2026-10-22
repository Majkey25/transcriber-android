package cz.majkey.prepis

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmChunkerTest {
    @Test
    fun `malformed media dimensions fail before allocation or multiplication`() {
        for (rate in listOf(0, -1, 192_001, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { pcmChunkSamples(rate) }
        }
        assertEquals(48_000 * 25, pcmChunkSamples(48_000))
        assertEquals(192_000 * 25, pcmChunkSamples(192_000))
        assertFailsWith<IllegalArgumentException> { PcmChunker(1, Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { PcmChunker(Int.MAX_VALUE, 1) }
        assertFailsWith<IllegalArgumentException> { PcmChunker(0, 1) }
    }

    @Test
    fun `stereo PCM16 is mixed to normalized mono chunks`() {
        val input = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(16_384).putShort(-16_384)
            putShort(16_384).putShort(16_384)
            putShort(-32_768).putShort(-32_768)
            putShort(0).putShort(32_767)
            flip()
        }

        val chunks = PcmChunker(channelCount = 2, chunkSamples = 2)
            .add(input, AudioFormat.ENCODING_PCM_16BIT)

        assertEquals(2, chunks.size)
        assertEquals(0f, chunks[0][0], 0.0001f)
        assertEquals(0.5f, chunks[0][1], 0.0001f)
        assertEquals(-1f, chunks[1][0], 0.0001f)
        assertEquals(0.5f, chunks[1][1], 0.0001f)
    }
}
