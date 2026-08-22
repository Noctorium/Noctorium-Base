# Providers

`MusicProvider` normalizes home sections, search, track lookup, and recommendations. YouTube Music and SoundCloud keep their authentication, API models, mapping, rate limiting, and errors inside separate implementations.

The application combines successful provider results. A temporary failure in one provider must not prevent the other from loading.

