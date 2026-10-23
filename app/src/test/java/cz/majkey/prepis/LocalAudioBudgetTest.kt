package cz.majkey.prepis

import android.media.AudioFormat
import java.io.IOException
import kotlin.test.assertFailsWith
import org.junit.Test

class LocalAudioBudgetTest {
    @Test
    fun `default duration permits exactly 24 hours of mono frames`() {
        val budget = LocalAudioBudget(nanoTime = { 0L })
        budget.chargeDecodedBytes(86_400 * 2, 1, 1, AudioFormat.ENCODING_PCM_16BIT)
        budget.chargeDecodedBytes(0, 1, 1, AudioFormat.ENCODING_PCM_16BIT)
        assertFailsWith<LocalAudioLimitException> {
            budget.chargeDecodedBytes(2, 1, 1, AudioFormat.ENCODING_PCM_16BIT)
        }
    }

    @Test
    fun `default compressed limit permits exactly four GiB without integer overflow`() {
        val budget = LocalAudioBudget(nanoTime = { 0L })
        budget.chargeCompressedBytes(Int.MAX_VALUE)
        budget.chargeCompressedBytes(Int.MAX_VALUE)
        budget.chargeCompressedBytes(2)
        budget.chargeCompressedBytes(0)
        assertFailsWith<LocalAudioLimitException> { budget.chargeCompressedBytes(1) }
        assertFailsWith<IOException> { budget.chargeCompressedBytes(-1) }
    }

    @Test
    fun `format changes retain duration and count frames rather than interleaved samples`() {
        val budget = LocalAudioBudget(maxDecodedDurationUs = 1_000_000, nanoTime = { 0L })
        budget.chargeDecodedBytes(8_000, 8_000, 1, AudioFormat.ENCODING_PCM_16BIT)
        budget.chargeDecodedBytes(192_000, 48_000, 2, AudioFormat.ENCODING_PCM_FLOAT)
        assertFailsWith<LocalAudioLimitException> {
            budget.chargeDecodedBytes(8, 48_000, 2, AudioFormat.ENCODING_PCM_FLOAT)
        }
    }

    @Test
    fun `repeated decoding passes share the remaining duration and input bytes`() {
        val budget = LocalAudioBudget(
            maxDecodedDurationUs = 1_000_000,
            maxCompressedBytes = 100,
            nanoTime = { 0L },
        )
        budget.chargeCompressedBytes(60)
        budget.chargeDecodedBytes(9_600, 8_000, 1, AudioFormat.ENCODING_PCM_16BIT)
        budget.chargeCompressedBytes(40)
        assertFailsWith<LocalAudioLimitException> { budget.chargeCompressedBytes(1) }
        assertFailsWith<LocalAudioLimitException> {
            budget.chargeDecodedBytes(9_600, 8_000, 1, AudioFormat.ENCODING_PCM_16BIT)
        }
        budget.chargeDecodedBytes(6_400, 8_000, 1, AudioFormat.ENCODING_PCM_16BIT)
    }

    @Test
    fun `each buffer rounds fractional microseconds up`() {
        val budget = LocalAudioBudget(maxDecodedDurationUs = 45, nanoTime = { 0L })
        budget.chargeDecodedBytes(2, 44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
        assertFailsWith<LocalAudioLimitException> {
            budget.chargeDecodedBytes(2, 44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
        }
    }

    @Test
    fun `invalid PCM dimensions encoding and partial frames are rejected`() {
        val budget = LocalAudioBudget(nanoTime = { 0L })
        for (rate in listOf(-1, 0, 192_001, Int.MAX_VALUE)) {
            assertFailsWith<IOException> {
                budget.chargeDecodedBytes(2, rate, 1, AudioFormat.ENCODING_PCM_16BIT)
            }
        }
        for (channels in listOf(-1, 0, 33, Int.MAX_VALUE)) {
            assertFailsWith<IOException> {
                budget.chargeDecodedBytes(2, 8_000, channels, AudioFormat.ENCODING_PCM_16BIT)
            }
        }
        assertFailsWith<IOException> { budget.chargeDecodedBytes(2, 8_000, 1, -1) }
        for (size in listOf(-1, 1, 3)) {
            assertFailsWith<IOException> {
                budget.chargeDecodedBytes(size, 8_000, 2, AudioFormat.ENCODING_PCM_16BIT)
            }
        }
        assertFailsWith<LocalAudioLimitException> {
            budget.chargeDecodedBytes(Int.MAX_VALUE - 1, 1, 1, AudioFormat.ENCODING_PCM_16BIT)
        }
    }

    @Test
    fun `default elapsed deadline is six hours including time between passes`() {
        var now = 10L
        val budget = LocalAudioBudget(nanoTime = { now })
        now += 6L * 60 * 60 * 1_000_000_000 - 1
        budget.checkTime()
        now++
        assertFailsWith<LocalAudioLimitException> { budget.checkTime() }
        assertFailsWith<LocalAudioLimitException> { budget.chargeCompressedBytes(1) }
        assertFailsWith<LocalAudioLimitException> {
            budget.chargeDecodedBytes(2, 8_000, 1, AudioFormat.ENCODING_PCM_16BIT)
        }
    }

    @Test
    fun `elapsed subtraction remains correct when the monotonic clock wraps`() {
        var now = Long.MAX_VALUE - 2
        val budget = LocalAudioBudget(maxElapsedNanos = 5, nanoTime = { now })
        now = Long.MIN_VALUE + 1
        budget.checkTime()
        now++
        assertFailsWith<LocalAudioLimitException> { budget.checkTime() }
    }
}
