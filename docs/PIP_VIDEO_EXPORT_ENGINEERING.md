# PIP Video Export — Engineering Plan

Status: composition planning and Media3 compositor adapter are implemented; export remains intentionally guarded.

## Current behavior
- PIP photos are composited into the primary clip export through Media3 OverlayEffect.
- PIP videos can be imported, persisted, edited, previewed, and moved on the PIP timeline.
- Export preflight rejects projects containing PIP video. Keep this guard until all acceptance checks pass.
- PIP-video audio is muted by default. Unmuted audio must not be silently discarded.

## Media3 compatibility
- All Media3 artifacts are pinned consistently to 1.8.1.
- Media3 1.8.0 introduced VideoCompositorSettings and a Composition demo for PIP layouts.
- Media3 1.6 moved builder functionality from OverlaySettings to StaticOverlaySettings; OverlaySettings is now the interface returned by dynamic overlay implementations.
- Existing photo/text overlay code is migrated to StaticOverlaySettings.Builder.

Official references:
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/media/media3/transformer/videocompositorsettings
- https://developer.android.com/reference/androidx/media3/common/VideoCompositorSettings
- https://developer.android.com/reference/androidx/media3/effect/StaticOverlaySettings.Builder

## Implemented compositor adapter
PipVideoCompositorSettings maps the deterministic input plan to Media3:
- PIP sequences are assigned input IDs from frontmost to backmost.
- The primary timeline is the final input and supplies output dimensions.
- PIP position, scale, rotation, and opacity are mapped to compositor settings.
- Inactive PIP intervals use alpha 0.
- Unit tests cover output size, transform mapping, active/inactive alpha, and primary visibility.

The adapter is not yet wired into buildComposition(). Unit tests validate mapping only; they do not prove Media3 gap timestamps, rendered frame composition, device codec compatibility, or audio synchronization.

## Required export architecture
- Use the primary timeline as the authoritative project clock and base layer.
- Decode each active PIP video independently and composite it above the base frame only while active.
- Apply timeline start, source trim, speed, position, scale, rotation, opacity, and layer order.
- Keep timeline time and source-media time separate.
- Support multiple simultaneous PIP videos with correct z-order.
- Keep the main video visible beneath inactive PIP regions and before/after PIP.
- Mix unmuted PIP audio with primary audio and music tracks, applying offset, trim, speed, and volume.
- Bound decoder/encoder resources; support cancellation, progress, and deterministic cleanup.
- Fail clearly if the device cannot satisfy requested codec, size, or frame rate.

## Acceptance tests before enabling export
### Visual/timeline
- PIP starts at zero, at a non-zero offset, and on a primary-clip boundary.
- PIP ends before, at, and after primary timeline end.
- Overlapping PIP videos respect editor z-order.
- Source trim and speed preserve expected source frames.
- Position, scale, rotation, and opacity match preview.
- Main video remains visible in inactive gaps.

### Audio
- Muted PIP contributes no sound.
- Unmuted PIP audio respects timeline offset, trim, speed, and volume.
- Primary audio and music tracks remain synchronized.
- Multiple PIP audio tracks have documented headroom/limiter behavior.

### Reliability
- Cancellation releases decoders, surfaces, encoders, and temporary files.
- Export failure leaves project and source media untouched.
- Short exports are tested on representative physical Android devices/codecs.
- Photo-only export and existing workflows remain regression-tested.

## Implementation rule
Do not weaken EditorViewModel.preflight() until compositor rendering, audio mixing, and device acceptance tests pass. A clear warning is preferable to silently omitting PIP video or audio.
