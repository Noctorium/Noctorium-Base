# Authentication

Spicetify will never request a Google or SoundCloud password inside the application.

- YouTube Music: Google's documented OAuth flow applies to the YouTube Data API, but it does not expose YouTube Music's personalized Home, Quick Picks, or radio surface. Spicetify therefore treats browser-established YouTube Music session/cookie import as a separate, explicitly unofficial provider session. Current yt-dlp guidance says YouTube OAuth login no longer works for yt-dlp. Cookies stay outside the repository and are always redacted from logs.
- SoundCloud: the current official API uses OAuth 2.1, but application registration is closed, so account access reuses the same cookie session mechanism as YouTube Music rather than an Authorization Code flow.
- Cookie sessions are checked, never assumed. A session is one of two sources — a browser profile yt-dlp reads (`--cookies-from-browser BROWSER[:PROFILE][::CONTAINER]`) or an exported Netscape `cookies.txt` (`--cookies FILE`) — and Spicetify stores only its location plus the time it last passed a check. Connecting runs one real yt-dlp request: YouTube must serve the subscriptions feed, which only a signed-in account sees; SoundCloud has no comparably cheap private surface, so its session is reported as ready rather than as a verified account. Failures are classified as unreadable cookies, readable cookies with no session, or an unrelated backend failure, and each carries the remediation for that case.
- Chromium-based browsers are the common failure: they lock the cookie database while running and, from Chrome 127 on Windows, encrypt it with App-Bound Encryption that yt-dlp cannot decrypt. Firefox is presented first for that reason, and an exported `cookies.txt` is the documented fallback.
- Last.fm: the official desktop flow obtains a request token, opens Last.fm authorization in the system browser, and exchanges the one-time authorized token for a session key. Spicetify ships with its own application key and shared secret, so a listener only clicks Connect, allows Spicetify in the browser window that opens, and sign-in finishes on its own; `SPICETIFY_LASTFM_API_KEY` and `SPICETIFY_LASTFM_SHARED_SECRET` override the built-in application. Session keys are stored securely and may be revoked by the user.
- ListenBrainz: the user supplies the token available from their ListenBrainz settings. Requests send it in the authorization header; Spicetify must never log it.
- Discord: direct desktop Rich Presence can operate through local RPC while the Discord desktop client is running. It requires a registered application ID but does not require the user to authenticate with Spicetify.

Authentication implementations are not yet enabled in Phase 1; the Settings screen accurately shows them as disconnected.

## Sources checked

These implementation constraints were checked against current primary documentation in August 2026:

- [SoundCloud API authentication](https://developers.soundcloud.com/docs/api/guide)
- [YouTube Data API OAuth](https://developers.google.com/youtube/v3/guides/authentication)
- [yt-dlp extractor authentication guidance](https://github.com/yt-dlp/yt-dlp/wiki/Extractors)
- [Last.fm desktop authentication](https://www.last.fm/api/desktopauth)
- [ListenBrainz API documentation](https://listenbrainz.readthedocs.io/)
- [Discord direct Rich Presence](https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence)
