# Accuracy benchmark

The target is at most 2% word error rate (WER), which equals at least 98% exact-word
accuracy. WER counts word substitutions, deletions, and insertions after lowercase
Unicode normalization and punctuation removal.

## Fixtures

- Synthetic Czech call: 13.1 seconds and 21 reference words.
- Archival Czech speech: 45 seconds and 94 reference words from the matching Czech
  subtitle track.

## Results

| Model | Synthetic WER | Synthetic accuracy | Archival WER | Archival accuracy |
| --- | ---: | ---: | ---: | ---: |
| Local Whisper Small INT8 on Android | 9.52% | 90.48% | 24.47% | 75.53% |
| Qwen3-ASR-1.7B on a host GPU | 9.52% | 90.48% | 26.60% | 73.40% |

Local Whisper also transcribed the 21-word English smoke fixture without a word
error. That result does not measure Czech accuracy.

The measured Czech results do not meet the 98% target. Qwen3-ASR-1.7B was not added
to the Android app because it did not improve either Czech fixture and is not viable
in the 2 GB test emulator. The test set is too small to predict general accuracy.

Cloud models require provider credentials, which were not available in the test
environment. Transcriber supports OpenAI GPT Transcribe, Groq Whisper Large V3,
Gemini, and xAI Speech to Text so each user can test the best option for their own
recordings. Verify important transcripts against the recording.

## Multilingual routing benchmark

On 2026-08-24, six clean neural-voice fixtures compared the local Android model
with two mobile candidates. Numbers are WER; lower is better.

| Language | Whisper Small | Omnilingual 300M | Whisper Medium | Selected engine |
| --- | ---: | ---: | ---: | --- |
| Czech | 28.57% | 14.29% | 4.76% | Omnilingual 300M |
| English | 4.17% | 12.50% | 4.17% | Whisper Small |
| German | 3.85% | 7.69% | 3.85% | Whisper Small |
| French | 16.00% | 8.00% | 16.00% | Omnilingual 300M |
| Spanish | 3.85% | 3.85% | 7.69% | Whisper Small |
| Polish | 4.76% | 14.29% | 4.76% | Whisper Small |

On the 94-word archival Czech fixture, both Omnilingual 300M and Whisper Medium
scored 20.21% WER. Whisper Small scored 24.47%. Whisper Medium needs 946 MB of
model files and used more than 2 GB working memory on the host, so it was rejected
for the 2 GB Android emulator.

Android verification matched the routing decision. English stayed on Whisper.
Czech used Omnilingual at 14.29% WER. French used Omnilingual at about 12% WER in
the Android run. The app peaked at 145 MB PSS and 183 MB RSS during captured smart
routing and did not crash.

These clean fixtures compare engines; they do not predict accuracy on arbitrary
calls. The smart local route still does not meet the 98% target.
