package app.noctorium.domain

/**
 * "1 track", not "1 tracks".
 *
 * Here rather than in either application because both of them count tracks, both of them got it wrong in
 * some places and right in others, and a rule that lives in one screen is a rule that the next screen will
 * be written without.
 */
fun pluralTracks(count: Int): String = if (count == 1) "1 track" else "$count tracks"
