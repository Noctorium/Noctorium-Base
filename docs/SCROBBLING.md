# Scrobbling

The scrobbling phase will introduce one `ScrobbleManager` fed by playback events and adapters for Last.fm and ListenBrainz. Threshold decisions, now-playing updates, offline persistence, deduplication, and bounded retry will be centralized rather than duplicated per integration.

