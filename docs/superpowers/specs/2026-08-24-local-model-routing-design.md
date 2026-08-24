# Local model routing design

## Goal

Improve local Czech and French transcription without degrading the other tested
languages or exceeding the current Android emulator's memory.

## Benchmark decision

The benchmark used six clean neural-voice fixtures with exact reference text.
The Czech archival fixture adds a 94-word real-speech check.

| Language | Whisper Small WER | Omnilingual 300M WER | Selected model |
| --- | ---: | ---: | --- |
| Czech | 28.57% | 14.29% | Omnilingual 300M |
| English | 4.17% | 12.50% | Whisper Small |
| German | 3.85% | 7.69% | Whisper Small |
| French | 16.00% | 8.00% | Omnilingual 300M |
| Spanish | 3.85% | 3.85% | Whisper Small |
| Polish | 4.76% | 14.29% | Whisper Small |

On the Czech archival fixture, Omnilingual scored 20.21% WER versus Whisper
Small's previous 24.47%. Whisper Medium also scored 20.21%, but used over 2 GB
host working memory and needs 946 MB of model files. It is not suitable for the
2 GB emulator.

## Runtime routing

- Keep one local profile and existing transcript migration paths.
- Download the verified Whisper Small and Omnilingual 300M INT8 files.
- For explicit Czech or French, run Omnilingual directly.
- For explicit other languages, run Whisper Small with its language hint.
- For Auto-detect, run Whisper Small first and read `OfflineRecognizerResult.lang`.
- If the detected language is Czech or French, release Whisper and rerun the
  recording with Omnilingual. Otherwise, keep the Whisper transcript.
- Never hold both recognizers in memory at the same time.

Omnilingual is a CTC model without punctuation. Capitalize each decoded chunk and
append a final period before applying the existing paragraph formatter.

## Model files

Pin Omnilingual to Hugging Face revision
`6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094`.

- `model.int8.onnx`: 365,352,120 bytes,
  SHA-256 `e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c`
- `tokens.txt`: 86,423 bytes,
  SHA-256 `a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31`

The first fresh local run downloads about 741 MB across both engines. Existing
users retain the verified Whisper files and download only Omnilingual.

## Verification

- Unit tests cover the routing table, majority language selection, and CTC chunk
  formatting.
- Existing profile, queue, and transcript migration tests stay green.
- Emulator QA removes only generated Auto transcript variants, reruns Czech and
  English, checks the selected source, captures memory, and checks the crash buffer.
