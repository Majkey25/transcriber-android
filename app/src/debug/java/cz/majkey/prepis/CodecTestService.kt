package cz.majkey.prepis

import android.net.Uri
import java.io.File

class CodecTestService : LocalTranscriptionService() {
    internal override fun transcribeSnapshot(audio: File, language: TranscriptionLanguage, budget: LocalAudioBudget): String {
        val decoder = AudioTranscriber(contentResolver, LocalModels(cacheDir, cacheDir), checkCancelled = {})
        var samples = 0
        repeat(if (language == TranscriptionLanguage.AUTO) 2 else 1) {
            decoder.decodeAudio(Uri.fromFile(audio), budget) { chunk, _ -> samples += chunk.size }
        }
        return when (language) {
            TranscriptionLanguage.FRENCH -> "a".repeat(MAX_TRANSCRIPT_BYTES)
            TranscriptionLanguage.GERMAN -> "a".repeat(MAX_TRANSCRIPT_BYTES + 1)
            else -> "Decoded $samples samples."
        }
    }
}
