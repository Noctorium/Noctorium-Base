# Development

## Foundation workflow

1. Install JDK 21.
2. Run `./gradlew test`.
3. Run `./gradlew run` for the mock-provider UI.
4. Run `./gradlew packageDistributionForCurrentOS` for packaging checks.

Mock providers remain available for focused tests. The default development app uses yt-dlp-backed public discovery and mpv-backed playback.

## Phase order

1. Foundation and mock UI
2. Main GUI completion
3. yt-dlp and mpv playback adapters
4. YouTube Music session/provider
5. SoundCloud OAuth 2.1 + PKCE/provider
6. Unified live home and provider-aware autoplay
7. Last.fm, ListenBrainz, and persistent retry
8. Discord Rich Presence
9. secure storage, diagnostics, polish, and packaging

Every phase must compile and pass its focused tests before the next integration is treated as complete.
