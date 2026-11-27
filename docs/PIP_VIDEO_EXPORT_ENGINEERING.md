# PIP Video Export — Engineering Plan

Status: investigation complete; implementation intentionally guarded.

## Current behavior

- PIP photos are composited into the primary clip export through Media3 `OverlayEffect`.
- PIP videos can be imported, persisted, edited, previewed, and moved on the PIP timeline.
- Export preflight currently rejects projects containing PIP video. Keep this guard until every acceptance check below passes.
- PIP-video audio is muted by default in the editor. Unmuted audio must not be silently discarded.

## Verified Media3 1.4.1 constraints

The app currently uses Media3 1.4.1.

1. `EditedMediaItemSequence` concatenates items; items inside one sequence do not overlap. In 1.4.1 it has no video gap insertion API.
2. `Composition.Builder` accepts multiple sequences and a `VideoCompositorSettings`.
3. The Media3 default compositor has a critical ordering trade-off: the first registered input is the primary stream (it determines output timestamps and dimensions), and registered inputs are composited in reverse registration order, with the first registered source on top.
4. Therefore, registering the primary timeline first gives it the correct output clock but also places it above later PIP inputs in the default compositor. Registering PIP first changes the primary clock/dimensions to a PIP source. A custom `VideoCompositorSettings` controls per-input geometry/alpha, but does not by itself change the default compositor's registration-order stacking rule.
5. The public Media3 layout guide demonstrates multi-input PIP layouts with `VideoCompositorSettings`; it does not remove the need to verify primary-stream ordering, gaps, and inactive input behavior for this editor's timeline.
6. Upgrading Media3 alone is not a fix for these constraints. Do not remove the current export guard based on a dependency bump.

Reference documentation:
- https://developer.android.com/reference/androidx/media3/effect/DefaultVideoCompositor
- https://developer.android.com/reference/androidx/media3/common/VideoCompositorSettings
- https://developer.android.com/media/media3/transformer/videocompositorsettings
- https://developer.android.com/reference/androidx/media3/transformer/Composition

## Required export architecture

Implement a dedicated video-layer compositor/export path (or a verified compositor implementation with equivalent control) that:

- Uses the existing primary timeline output as the base layer and the authoritative output clock.
- Decodes each active PIP video layer independently and composites it above the base frame.
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
