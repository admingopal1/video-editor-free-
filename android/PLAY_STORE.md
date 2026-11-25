# Google Play listing checklist

**App name:** Video Editor (change to your brand; max 30 chars)
**Short description (80):** Trim, merge, speed up, add text, filters and music. Export HD video.
**Full description:**
Edit videos fast with a clean, no-ads editor.
- Merge many clips on one timeline, reorder, duplicate, delete
- Trim and split exactly where you want
- Speed 0.25x to 4x
- Volume control, mute, background music
- Rotate, flip, zoom
- Brightness, contrast, saturation, warmth + 6 filters
- Text on video with colour, size and position
- 16:9, 9:16, 1:1, 4:5, 21:9 canvas; 480p / 720p / 1080p export
- Named projects with thumbnails, undo / redo, automatic saving
- Whole-project preview, drag to reorder clips, text with timing and animation
- Keyframe animation (position, scale, rotation, opacity), speed ramps, transitions
- Up to 4 audio tracks with waveforms, voice-over recording
- Export presets for Reels/Shorts, YouTube, Instagram and WhatsApp; 24/30 fps and bitrate options
- Works offline, no account, no watermark

**Category:** Video Players & Editors
**Data safety form:** App collects no data, shares no data, no data encryption needed (nothing leaves device).
**Content rating:** Everyone (fill the IARC questionnaire: no violence, no user-generated content sharing).
**Permissions:** Microphone (only when you tap "Record voice"; recordings stay on your device), Notifications (export progress, optional). Photo and file access uses the system pickers.
**Privacy policy URL:** host PRIVACY_POLICY.md (e.g. GitHub Pages) and paste the link.
**Assets you must make:** 512x512 icon, 1024x500 feature graphic, 2-8 phone screenshots.
**Upload:** the signed `.aab` from the GitHub Actions artifact "VideoEditor-release".

**Play Console declarations**
- Foreground service type: *Media processing* (and *Data sync* on Android 14 and below) — used only while exporting a video so it can finish when the app is in the background.
- Notifications: shows export progress (permission is optional).
