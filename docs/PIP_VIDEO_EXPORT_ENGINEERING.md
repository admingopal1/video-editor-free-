# PIP Video Export — Engineering Plan

Status: compositor adapter scaffold added; export remains intentionally guarded.

## Current behavior

- PIP photos are composited into the primary clip export through Media3 `OverlayEffect`.
- PIP videos can be imported, persisted, edited, previewed, and moved on the PIP timeline.
- Export preflight currently rejects projects containing PIP video. Keep this guard until every acceptance check below passes.
- PIP-video audio is muted by default in the editor. Unmuted audio must not be silently discarded.

## Compositor adapter scaffold (Media3 1.5.1)

- `PipVideoCompositorSettings` now maps the existing deterministic input plan to Media3 `VideoCompositorSettings`.
- It keeps the primary timeline input as the output-size/background source and applies each PIP layer's position, scale, rotation, and opacity.
- Outside a PIP layer's half-open timeline interval, it returns alpha 0 for that input. This is intended to prevent an inactive video-gap placeholder from covering the primary video.
- This is a reusable adapter only. It is not wired into `buildComposition()`, and export preflight remains unchanged.
- JVM tests cover the plan-to-layer mapping assumptions and timeline/source clock boundaries. They do not prove Media3's actual gap timestamp behavior or rendered transparency.

## Verified Media3 compositor behavior

The official Media3 compositor guide demonstrates that input 0 is positioned as the foreground PIP overlay and the second input is presented in the background. The app's pure composition-plan helper therefore assigns PIP inputs before the primary timeline input.

Current plan convention:
- PIP video inputs receive IDs starting at 0, ordered from highest `layerOrder` (frontmost) to lowest.
- The primary timeline is assigned the final input ID after the PIP inputs.
- `VideoCompositorSettings` exposes output-size selection and per-input `OverlaySettings`, including position, scale, rotation, and alpha.
- Multiple `EditedMediaItemSequence` instances and custom compositor settings are supported by the documented Composition API.
- A gap in a video sequence must be tested: alpha 0 is now the adapter's defensive setting, but the pinned 1.5.1 export path still needs a rendered-frame test.

Official references:
- https://developer.android.com/media/media3/transformer/videocompositorsettings
- https://developer.android.com/reference/androidx/media3/common/VideoCompositorSettings
- https://developer.android.com/reference/androidx/media3/transformer/Composition.Builder
- https://developer.android.com/reference/androidx/media3/transformer/EditedMediaItemSequence.Builder

## Required export architecture

Implement a verified multi-sequence composition path that:
- Uses the existing primary timeline as the authoritative project clock and base layer.
- Decodes each active PIP video independently and composites it above the base frame only while active.
- Applies each layer's `timelineStartMs`, source `startMs/endMs`, `speed`, `x/y`, `scale`, `rotation`, `opacity`, and `layerOrder`.
- Keeps timeline time and source-media time separate.
- Supports multiple simultaneous PIP videos with correct z-order.
- Keeps the main video visible beneath transparent/unused PIP regions and before/after PIP visibility.
- Mixes each unmuted PIP audio stream with existing primary audio and music tracks, applying offset, trim, speed, and volume.
- Uses bounded decoder/encoder resources, cancellation, progress, and deterministic cleanup.
- Fails clearly if the device cannot satisfy requested output codec/size/frame rate.

## Acceptance tests before enabling export

### Timeline and visual composition
- PIP starts at 0 ms, at a non-zero offset, and exactly on a primary-clip boundary.
- PIP ends before, at, and after primary timeline end.
- Two or more PIP videos overlap; z-order matches editor layer order.
- PIP source trim and speed preserve expected source frames.
- Position, scale, rotation, and opacity match preview.
- Main video remains visible beneath inactive/transparent PIP regions and before/after PIP.

### Audio
- Muted PIP video contributes no sound.
- Unmuted PIP audio starts at PIP timeline offset, respects source trim/speed, and applies volume.
- Primary audio and all music tracks remain present and synchronized.
- Multiple PIP audio tracks do not clip unexpectedly; mixing has a documented headroom/limiter strategy.

### Reliability and compatibility
- Export cancellation releases every decoder, surface, encoder, and temporary file.
- Export failure leaves project and source media untouched.
- Test short exports on representative physical Android devices/codecs.
- Keep photo-only export and all existing workflows regression-tested.

## Implementation rule

Do not weaken `EditorViewModel.preflight()` until the compositor and audio mixer pass the acceptance tests. A visible warning is preferable to an export that silently omits PIP video or audio.


Dependency compatibility note: the compositor adapter requires `VideoCompositorSettings`; Media3 1.5.1 does not expose this API. The project is being aligned to Media3 1.8.1 for validation. Video-PIP export remains guarded until real rendered output and audio tests pass.
