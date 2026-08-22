# Authentication

Spice will never request a Google or SoundCloud password inside the application.

- YouTube Music: Google's documented OAuth flow applies to the YouTube Data API, but it does not expose YouTube Music's personalized Home, Quick Picks, or radio surface. Spice therefore treats browser-established YouTube Music session/cookie import as a separate, explicitly unofficial provider session. Current yt-dlp guidance says YouTube OAuth login no longer works for yt-dlp. Cookies stay outside the repository and are always redacted from logs.
- SoundCloud: the current official API uses OAuth 2.1. Account access uses a system-browser Authorization Code flow with required PKCE, random `state`, a localhost-only callback, token expiry handling, and single-use refresh-token rotation.
- Last.fm: the official desktop flow obtains a request token, opens Last.fm authorization in the system browser, and exchanges the one-time authorized token for a session key. Session keys are stored securely and may be revoked by the user.
- ListenBrainz: the user supplies the token available from their ListenBrainz settings. Requests send it in the authorization header; Spice must never log it.
- Discord: direct desktop Rich Presence can operate through local RPC while the Discord desktop client is running. It requires a registered application ID but does not require the user to authenticate with Spice.

Authentication implementations are not yet enabled in Phase 1; the Settings screen accurately shows them as disconnected.

## Sources checked

These implementation constraints were checked against current primary documentation in August 2026:

- [SoundCloud API authentication](https://developers.soundcloud.com/docs/api/guide)
- [YouTube Data API OAuth](https://developers.google.com/youtube/v3/guides/authentication)
- [yt-dlp extractor authentication guidance](https://github.com/yt-dlp/yt-dlp/wiki/Extractors)
- [Last.fm desktop authentication](https://www.last.fm/api/desktopauth)
- [ListenBrainz API documentation](https://listenbrainz.readthedocs.io/)
- [Discord direct Rich Presence](https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence)
