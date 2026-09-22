# Playback

The implemented basic flow is `MusicProvider → Track → YtDlpService → PlaybackEngine → mpv`. Public discovery and stream resolution run away from the UI thread. Play, pause, resume, volume, next, previous, and stop are connected to the managed mpv process.

Resolved media URLs are ephemeral and will be obtained close to playback rather than persisted. Process arguments will be passed as separate `ProcessBuilder` items; user-controlled text will never be concatenated into a shell command.

The shared queue is already provider-aware. Manual entries can mix services, while its playback context keeps autoplay recommendations on the provider that started the session.

## yt-dlp baseline

Use yt-dlp `2026.06.09` or newer. That release fixed a cookie leak affecting external curl-based downloads. Noctorium will check the detected version in Diagnostics and will never silently fetch executables from untrusted locations.
