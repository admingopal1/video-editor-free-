# Cutroom — Free Browser Video Editor

A lightweight, client-side video editor. Open the page in a modern browser; no account or server upload is required.

## Features

- Import a local video and preview it in the editor.
- Set start and end points to trim a clip.
- Seek, play, pause, and restart playback.
- Apply grayscale, sepia, cinematic, vivid, brightness, and contrast filters.
- Rotate video in 90° steps.
- Add a styled text overlay.
- Adjust original audio volume.
- Export a WebM video at low, standard, or high quality.

## Run locally

You can open `index.html` directly in a modern browser, or serve the folder with any static web server:

```bash
python -m http.server 8000
```

Then visit http://localhost:8000.

## Export notes

- Export is rendered in real time, so a 60-second clip takes about 60 seconds to export.
- Output is WebM. MP4 encoding is not currently included.
- Audio capture depends on browser support. Chrome and Edge are recommended.
- Keep the tab open while exporting. Large videos require sufficient device memory.

## Privacy

Video processing happens in the browser. The app does not upload your selected video to a backend.

## Browser support

Use a recent Chromium-based browser for the most reliable MediaRecorder and canvas capture support.
