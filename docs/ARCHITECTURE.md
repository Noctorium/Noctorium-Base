# Spiceity architecture

Spiceity uses Kotlin and Compose Desktop for a native JVM application with coroutines and `StateFlow` for predictable, non-blocking state updates.

## Layers

- `domain`: normalized provider-aware music and playback models.
- `providers`: a stable `MusicProvider` boundary plus isolated YouTube Music and SoundCloud implementations.
- `playback`: shared queue and, in the playback phase, isolated yt-dlp and mpv adapters.
- `core`: application orchestration and unidirectional state.
- `ui`: Compose screens and reusable presentation components.
- Future layers: storage, authentication, scrobbling, Discord presence, diagnostics.

The UI never calls provider-specific endpoints. Providers can fail independently, and orchestration combines successful results. A manual queue may contain tracks from both services, but its `PlaybackContext` preserves the provider that seeded autoplay.

External tools are adapter boundaries rather than application-wide dependencies. `YtDlpService` will resolve ephemeral streams close to playback; `PlaybackEngine` will hide mpv JSON IPC behind portable commands and state.

