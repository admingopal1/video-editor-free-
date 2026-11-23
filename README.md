# Cutroom — Free Browser Video Editor

Cutroom is a local-first video editing workspace built with browser APIs. It runs as a static site and does not require an account or backend.

## Current features

- Import and preview a local video.
- Trim by setting start and end points; seek, play, pause, and restart.
- Apply presets: original, monochrome, vintage, cinematic, vivid, bright, and high contrast.
- Fine-tune brightness, contrast, and saturation.
- Rotate by 90° and flip horizontally or vertically.
- Add a text overlay with custom color and size.
- Adjust playback speed from 0.25× to 2×.
- Adjust source audio volume.
- Undo and redo editing-setting changes.
- Save and load project settings as JSON. The video itself is not embedded in the project file; re-import the original media when reopening.
- Export a rendered WebM file at low, standard, or high quality.

## Run

Open `index.html` in a modern browser, or run a static server:

```bash
python -m http.server 8000
```

Visit http://localhost:8000.

## Export limitations

- Export is rendered in real time; a 60-second clip can take roughly 60 seconds.
- Export currently produces WebM, not MP4.
- This version edits one source clip at a time. It does not yet provide a multi-track, multi-clip timeline, transitions, keyframes, or a separate music track.
- Audio capture and MediaRecorder codec support depend on the browser. Recent Chrome or Edge is recommended.
- Keep the tab open during export. Large files require adequate device memory.

## Privacy

Selected media is processed locally in the browser. Cutroom does not upload the video to a server.

## Browser support

Use a current Chromium-based browser for the most reliable canvas capture and MediaRecorder support.
