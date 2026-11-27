# PIP Video Export — Engineering Plan

Status: investigation in progress; implementation intentionally guarded.

## Current behavior

- PIP photos are composited into the primary clip export through Media3 `OverlayEffect`.
- PIP videos can be imported, persisted, edited, previewed, and moved on the PIP timeline.
- Export preflight currently rejects projects containing PIP video. Keep this guard until every acceptance check below passes.
- PIP-video audio is muted by default in the editor. Unmuted audio must not be silently discarded.

## Verified Media3 compositor behavior

The official Media3 compositor guide demonstrates that input 0 is positioned as the foreground PIP overlay and the second input is presented in the background. The app's pure composition-plan helper must therefore assign PIP inputs before the primary timeline input; reserving input 0 for the primary timeline would contradict the documented example.

Current plan convention:
- PIP video inputs receive IDs starting at 0, ordered from highest `layerOrder` (frontmost) to lowest.
- The primary timeline is assigned the final input ID after the PIP inputs.
- This is deterministic input planning only. It does not yet prove that multiple overlapping PIP inputs composite in the exact editor z-order on every supported device.

Verified API facts:

1. `VideoCompositorSettings` exposes output-size selection and per-input `OverlaySettings`, including position, scale, rotation, and alpha.
2. The official guide's moving-PIP example explicitly treats input 0 as the overlay and later input as the background.
3. The composition API accepts multiple `EditedMediaItemSequence` instances and custom `VideoCompositorSettings`.
4. The current public API reference documents `EditedMediaItemSequence.Builder.addGap(durationUs)` as a period with no media. The reference page is not version-pinned, so the exact availability and runtime behavior in the app's pinned Media3 1.5.1 must be verified against that artifact before relying on it.
5. A gap in a video sequence must be tested for compositor behavior: it must leave the primary frame visible rather than render an opaque black frame over it.
6. `VideoCompositorSettings` controls input layout; it does not by itself align independent source clocks, mix independently offset audio, or prove behavior for multiple overlapping PIP streams.
7. Do not assume a dependency upgrade or a pure timeline plan alone solves these requirements. Verify the exact API/version and output behavior with a short instrumented export before removing the preflight guard.

Official references:
- https://developer.android.com/media/media3/transformer/videocompositorsettings
- https://developer.android.com/reference/androidx/media3/common/VideoCompositorSettings
- https://developer.android.com/reference/androidx/media3/transformer/Composition.Builder
- https://developer.android.com/reference/androidx/media3/transformer/EditedMediaItemSequence.Builder
- https://developer.android.com/reference/androidx/media3/effect/VideoCompositor

## Required export architecture

Implement a dedicated video-layer compositor/export path (or a verified Media3 composition path with equivalent control) that:

- Uses the existing primary timeline output as the authoritative project clock and base layer.
- Decodes each active PIP video layer independently and composites it above the base frame only while active.
- Applies each layer's `timelineStartMs`, source `startMs/endMs`, `speed`, `x/y`, `scale`, `rotation`, `opacity`, and `layerOrder`.
- Keeps timeline time and source-media time separate. For a layer at project time `t`, source time is derived from the layer's own start and playback speed only while the layer is active.
- Supports multiple simultaneous PIP videos without one layer replacing another.
- Keeps the main video visible beneath transparent/unused PIP regions and before/after PIP visibility.
- Mixes each unmuted PIP audio stream with the existing primary audio and music tracks, applying the layer volume and timeline offset. Muted layers contribute no audio.
- Uses bounded decoder/encoder resources, cancellation, progress, and deterministic cleanup of temporary files.
- Fails with a clear error if the device cannot satisfy the requested output codec/size/frame rate. Never report a false successful export.

## Acceptance tests before enabling export

### Timeline and visual composition
- PIP starts at 0 ms, at a non-zero offset, and exactly on a primary-clip boundary.
- PIP ends before, at, and after the primary timeline end.
- Two or more PIP videos overlap; z-order matches the editor's layer order.
- PIP source trim and speed changes preserve the expected source frame at each project timestamp.
- Position, scale, rotation, and opacity match preview.
- Main video remains visible beneath transparent/unused PIP regions and before/after PIP visibility.

### Audio
- Muted PIP video contributes no sound.
- Unmuted PIP audio starts at the PIP timeline offset, respects source trim and playback speed, and applies volume.
- Primary audio and all music tracks remain present and synchronized.
- Multiple PIP audio tracks do not clip unexpectedly; mixing has a documented headroom/limiter strategy.

### Reliability and compatibility
- Export cancellation releases every decoder, surface, encoder, and temporary file.
- Export failure leaves the project and source media untouched.
- Test short exports on representative physical Android devices and codecs before enabling the feature.
- Keep photo-only export and all existing editor workflows regression-tested.

## Implementation rule

Do not weaken `EditorViewModel.preflight()` until the compositor and audio mixer pass the acceptance tests. A visible warning is preferable to an export that silently omits PIP video or audio.
## Current verification boundary

- The pure PIP composition-plan unit tests passed in CI on commit `a52ed260afdc5dff3552d4313f7c1d897b30402b` (Build #279). They cover deterministic input IDs/order, exclusion of still images and invalid layers, source-clock mapping, exclusive end timing, and saturated timestamp conversion.
- The follow-up documentation commit `db0cdfcc3afb5738b85a49e62efbca63a5bc4ce6` is one commit later than that successful build. The current PR head therefore still needs its own CI run before it is marked fully verified.
- These JVM tests validate planning math only; they do not validate actual Media3 frame compositing, inactive gaps, multi-layer z-order, independent audio mixing, cancellation, or device compatibility. Keep PIP-video export disabled until the acceptance checks above pass.
