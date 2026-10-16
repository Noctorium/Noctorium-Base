# Authentication

Noctorium will never request a Google or SoundCloud password inside the application.

- YouTube Music: Google's documented OAuth flow applies to the YouTube Data API, but it does not expose YouTube Music's personalized Home, Quick Picks, or radio surface. Noctorium therefore treats browser-established YouTube Music session/cookie import as a separate, explicitly unofficial provider session. Current yt-dlp guidance says YouTube OAuth login no longer works for yt-dlp. Cookies stay outside the repository and are always redacted from logs.
- SoundCloud: the current official API uses OAuth 2.1, but application registration is closed, so account access reuses the same cookie session mechanism as YouTube Music rather than an Authorization Code flow.
- Cookie sessions are checked, never assumed. A session is one of two sources — a browser profile yt-dlp reads (`--cookies-from-browser BROWSER[:PROFILE][::CONTAINER]`) or an exported Netscape `cookies.txt` (`--cookies FILE`) — and Noctorium stores only its location plus the time it last passed a check. Connecting runs one real yt-dlp request: YouTube must serve the subscriptions feed, which only a signed-in account sees; SoundCloud has no comparably cheap private surface, so its session is reported as ready rather than as a verified account. Failures are classified as unreadable cookies, readable cookies with no session, or an unrelated backend failure, and each carries the remediation for that case.
- Chromium-based browsers are the common failure: they lock the cookie database while running and, from Chrome 127 on Windows, encrypt it with App-Bound Encryption that yt-dlp cannot decrypt. Firefox is presented first for that reason, and an exported `cookies.txt` is the documented fallback.
- Apple Music, Deezer and Tidal are libraries and catalogues to read, never sources to play from: none of them lets another program play its audio, so their songs are matched to the same recording on YouTube Music when played, as Spotify's are. Each is marked as in beta (`ProviderType.inBeta`), and every player shows `BETA_WARNING` above its setup.
- Deezer: no sign-in at all, since Deezer accepts no new OAuth applications. The listener gives their public profile's link or number, which is checked against the public API before it is kept.
- Tidal: the official OAuth 2.1 authorization-code flow with PKCE at login.tidal.com, the reply caught on `http://127.0.0.1:8889/noctorium/tidal`, and the client-credentials grant for reading the catalogue signed out. Noctorium has no Tidal application of its own yet, so the listener registers one at developer.tidal.com; its client id and secret are kept in the credential store. Tidal's own apps' client ids are never used.
- Apple Music: unofficial. The catalogue is read with the developer token Apple's own web player is served, found in that player's script at run time, or with a MusicKit developer token of the listener's own. The library is read with the `media-user-token` the web player keeps after the listener signs in on Apple's own page in the embedded browser; it is checked with Apple and kept in the credential store, never logged.
- Last.fm: the official desktop flow obtains a request token, opens Last.fm authorization in the system browser, and exchanges the one-time authorized token for a session key. Noctorium ships with its own application key and shared secret, so a listener only clicks Connect, allows Noctorium in the browser window that opens, and sign-in finishes on its own; `NOCTORIUM_LASTFM_API_KEY` and `NOCTORIUM_LASTFM_SHARED_SECRET` override the built-in application. Session keys are stored securely and may be revoked by the user.
- ListenBrainz: the user supplies the token available from their ListenBrainz settings. Requests send it in the authorization header; Noctorium must never log it.
- Discord: direct desktop Rich Presence can operate through local RPC while the Discord desktop client is running. It requires a registered application ID but does not require the user to authenticate with Noctorium.

Authentication implementations are not yet enabled in Phase 1; the Settings screen accurately shows them as disconnected.

## Sources checked

These implementation constraints were checked against current primary documentation in August 2026:

- [SoundCloud API authentication](https://developers.soundcloud.com/docs/api/guide)
- [YouTube Data API OAuth](https://developers.google.com/youtube/v3/guides/authentication)
- [yt-dlp extractor authentication guidance](https://github.com/yt-dlp/yt-dlp/wiki/Extractors)
- [Last.fm desktop authentication](https://www.last.fm/api/desktopauth)
- [ListenBrainz API documentation](https://listenbrainz.readthedocs.io/)
- [Discord direct Rich Presence](https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence)
