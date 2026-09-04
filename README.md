# Spiceity

Spiceity is a Kotlin desktop music player designed to bring YouTube Music and SoundCloud into one coherent interface: one home, one search, one library, one queue, and one player. Provider-aware playback context keeps automatic recommendations on the service that started the listening session.

> Spiceity is an independent third-party client. It is not affiliated with Google, YouTube, SoundCloud, Last.fm, ListenBrainz, or Discord.

## Current status

The current development build includes the Compose Desktop foundation, original dark theme, unified domain model, live public YouTube/SoundCloud discovery through yt-dlp, mpv audio playback, unified Home and Search views, shared queue, persistent player surface, Now Playing view, Settings shell, and queue/context tests.

Authenticated accounts, personalized feeds, scrobbling, secure credential storage, and Discord integration are intentionally not presented as working before their respective phases are implemented.

## Requirements

- JDK 21
- Windows or Linux
- Current versions of `yt-dlp` and `mpv` (Spiceity also checks `%LOCALAPPDATA%/Spiceity/bin`)

## Run

```powershell
./gradlew.bat run
```

On Linux:

```bash
./gradlew run
```

Run tests and create a native package with:

```bash
./gradlew test
./gradlew packageDistributionForCurrentOS
```

## Configuration

Copy `.env.example` only for developer-owned integration identifiers. User tokens and cookies will be stored outside the repository through the future secure credential-store layer. Never commit `.env`, cookie exports, tokens, or credentials.

## Design principles

- Both providers appear together by default; filters are optional.
- User-created queues may mix providers.
- Automatic recommendations follow the provider stored in `PlaybackContext`.
- Provider network and authentication details stay behind `MusicProvider`.
- UI development uses mock providers and does not require live account traffic.
- Failed or unsupported provider operations must never be shown as successful.

See [Architecture](docs/ARCHITECTURE.md) and [Development](docs/DEVELOPMENT.md).
