# Noctorium

Noctorium is a music player for YouTube Music and SoundCloud, with one home, one search, one library, one
queue and one player across a desktop and a phone. This repository is the half of it that is the same on
both: the domain model, the Spotify library and the matcher that resolves it, playlists, the queue, settings,
scrobbling, lyrics, the account, Connect between devices, and the update check.

> Noctorium is an independent third-party client. It is not affiliated with Google, YouTube, SoundCloud,
> Spotify, Last.fm, ListenBrainz or Discord.

## The repositories

| Repository | What it is |
| --- | --- |
| **Noctorium-Base** (this one) | `core`: the shared Kotlin library. A plain JVM library, because Android runs the same bytecode the desktop does. |
| [Noctorium-Desktop](https://github.com/Noctorium/Noctorium-Desktop) | The Windows and Linux application. Compose Desktop, mpv for audio, yt-dlp for the services, an embedded Chromium for sign-in. |
| [Noctorium-Mobile](https://github.com/Noctorium/Noctorium-Mobile) | The Android application. Compose, Media3 for audio, NewPipeExtractor for the services, the system WebView for sign-in. |
| [Noctorium-Installer](https://github.com/Noctorium/Noctorium-Installer) | The release pipeline and the releases themselves: installers, packages and the APK, with checksums. This is what the in-app updater watches. |
| [Noctorium-Service](https://github.com/Noctorium/Noctorium-Service) | Accounts and listening statistics, on Vercel. One account works in the player and on the website. |

## How the applications use this

Each application's `settings.gradle.kts` includes `core` from a checkout of this repository, found in this
order:

1. `NOCTORIUM_BASE`, an environment variable naming the checkout, when set.
2. `../Noctorium-Base`, a checkout beside the application's own. This is the arrangement for working on
   both at once: an edit here is seen by the application's next build without any pointer to move.
3. `base/`, the git submodule the application carries. This is what a clean clone and the build servers
   use; `git clone --recursive` brings it, or `git submodule update --init` afterwards.

The submodule pins a commit of this repository, so a release is reproducible. Move it in the application
repository when the core has changed in a way the application needs (`git submodule update --remote base`
and commit the new pointer).

## The rule this module lives by

Nothing in `core` may touch an API a phone does not have. That rules out `java.awt`, `javax.imageio`,
`java.net.http` (absent from Android at every API level) and `com.sun.net.httpserver`. It does not rule out
`java.nio.file`, which Android has had since API 26, the minimum the phone sets. Where the platforms
genuinely differ — where audio is decoded, how a stream address is found, where a sign-in happens, where a
secret is kept — `core` declares an interface and the application answers it.

## Building and testing

```bash
./gradlew :core:test
```

JDK 21. Nothing else is needed; there is no application here to run.

## Documentation

`docs/` describes the whole system: architecture, playback, providers, authentication, scrobbling,
troubleshooting, development. Releasing is described in Noctorium-Installer, where it happens.
