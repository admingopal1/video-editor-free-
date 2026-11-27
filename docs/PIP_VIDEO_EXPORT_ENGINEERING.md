# PIP Video Export — Engineering Plan

Status: investigation complete; implementation intentionally guarded.

## Current behavior

- PIP photos are composited into the primary clip export through Media3 `OverlayEffect`.
- PIP videos can be imported, persisted, edited, previewed, and moved on the PIP timeline.
- Export preflight currently rejects projects containing PIP video. Keep this guard until every acceptance check below passes.
- PIP-video audio is muted by default in the editor. Unmuted audio must not be silently discarded.

## Verified Media3 1.4.1 constraints

The app currently uses Media3 1.4.1. The official Media3 compositor guide demonstrates that the first sequence (input 0) can be positioned as the overlay and the second sequence as the background. Therefore the earlier assumption that the first registered input is necessarily drawn over later inputs is not supported; do not rely on that claim.

Verified API facts:

1. `VideoCompositorSettings` exposes output-size selection and per-input `OverlaySettings`, including position, scale, rotation, and alpha.
2. The official guide's example uses input 0 as the foreground PIP and the other input as the background. This demonstrates controllable per-input geometry, but does not itself establish how to select a primary timeline clock when inputs have different start times and durations.
3. The composition API accepts multiple `EditedMediaItemSequence` instances and custom `VideoCompositorSettings`.
4. The app's Media3 1.4.1 `EditedMediaItemSequence` API has no video-gap insertion method. A PIP sequence that starts later than the main timeline therefore needs a verified way to represent its inactive interval without rendering a black/transparent frame over the main image.
5. `VideoCompositorSettings` controls input layout; it does not by itself create missing frames, align independent source clocks to the editor's project clock, or mix independently offset audio.
6. Do not assume a dependency upgrade alone solves these timeline and audio requirements. Verify the exact API/version and output behavior with a short instrumented export before removing the preflight guard.

Official references:
- https://developer.android.com/media/media3/transformer/videocompositorsettings
- https://developer.android.com/reference/androidx/media3/common/VideoCompositorSettings
- https://developer.android.com/reference/androidx/media3/transformer/Composition.Builder
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
