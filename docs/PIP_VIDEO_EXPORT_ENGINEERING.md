# PIP Video Export — Engineering Status

Status: the Media3 multi-sequence composition is wired into `buildComposition()`. Export is still intentionally blocked by `EditorViewModel.preflight()` until rendered-video, audio, and device acceptance tests pass.

## Current behavior

- PIP photos are composited into the primary clip export through Media3 `OverlayEffect`.
- PIP videos can be imported, persisted, edited, previewed, and moved on the PIP timeline.
- `buildComposition()` now constructs a Media3 sequence for each valid PIP video, followed by the primary timeline and any additional audio sequences.
- PIP video sequences preserve source trim and speed, timeline offset, layer transform, opacity, and optional audio. Muted PIP layers are configured as video-only.
- Each PIP sequence is extended to the project duration with a trailing gap. The compositor sets the PIP alpha to zero outside that layer's active interval.
- Export preflight still rejects projects containing PIP video. Do not remove this guard based on unit tests or a successful APK build alone.
- PIP-video audio is muted by default. Unmuted audio must not be silently discarded.

## Media3 compatibility

All Media3 artifacts are pinned consistently to **1.10.1**. The implementation uses `EditedMediaItemSequence`, `Composition.Builder.setVideoCompositorSettings()`, and `VideoCompositorSettings`.

Official references:
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/media/media3/transformer/videocompositorsettings
- https://developer.android.com/reference/androidx/media3/common/VideoCompositorSettings
- https://developer.android.com/reference/androidx/media3/effect/StaticOverlaySettings.Builder

## Implemented composition architecture

- Valid video PIP layers are assigned input IDs from frontmost to backmost; the primary timeline is appended after PIP sequences.
- The primary stream supplies output dimensions.
- PIP position, scale, rotation, and opacity are mapped to compositor settings.
- PIP alpha is zero before its timeline start and at/after its exclusive timeline end.
- Each PIP sequence gets a leading gap for its timeline offset and a trailing gap through project end. This avoids a secondary stream ending early and leaving its last frame visible.
- Source clipping and `EditedMediaItem.setSpeed()` keep the PIP video and optional PIP audio on the same source clock.
- JVM tests cover deterministic input ordering, trim/speed clock mapping, timeline visibility, gap duration, output size selection, and transform mapping.

These tests verify planning and API wiring only. They do **not** prove that a real encoded file has correct pixels, timestamps, audio mix, or device compatibility.

## Acceptance tests required before enabling export

### Visual and timeline
- PIP starts at zero, at a non-zero offset, and on a primary-clip boundary.
- PIP ends before, at, and after primary timeline end.
- Overlapping PIP videos respect editor z-order.
- Source trim and speed preserve expected source frames.
- Position, scale, rotation, and opacity match preview.
- Main video remains visible before, between, and after PIP intervals.
- Photo-only export and existing single-track export remain unchanged.

### Audio
- Muted PIP contributes no sound.
- Unmuted PIP audio respects timeline offset, trim, speed, and volume.
- Primary audio and music tracks remain synchronized.
- Multiple PIP audio tracks have documented headroom/limiter behavior.
- Audio is present and decodable in the final exported file.

### Reliability and device coverage
- Cancellation releases decoders, surfaces, encoders, and temporary files.
- Export failure leaves project and source media untouched.
- Short exports are tested on representative Android devices and codecs.
- Output duration, dimensions, frame rate, and audio/video tracks are inspected from the produced file.

## Safety rule

Do not weaken `EditorViewModel.preflight()` until the rendered-video and audio acceptance checks pass and representative device testing is complete. A clear warning is preferable to silently omitting PIP video or audio.
