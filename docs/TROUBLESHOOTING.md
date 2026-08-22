# Troubleshooting

## The project does not start

Confirm that JDK 21 is installed and `JAVA_HOME` points to it. Then run `./gradlew --version` and `./gradlew test`.

## No real music appears

Phase 1 intentionally uses mock providers. Live authentication and playback are not claimed as complete yet.

## Integration secrets

Never paste tokens or cookies into source files. Use developer environment configuration only where documented; future user credentials will use OS-backed secure storage.

