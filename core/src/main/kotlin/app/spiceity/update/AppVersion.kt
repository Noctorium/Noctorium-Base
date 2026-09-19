package app.spiceity.update

/**
 * What version this process is, for the parts of Spiceity that have to say so out loud to somebody else.
 *
 * A global is not a thing to reach for lightly, and this is one. The justification is that the version of
 * the running program is genuinely process-wide -- there is exactly one right answer and nothing can
 * sensibly disagree about it -- and the alternative was threading a string through four constructors to
 * reach a single JSON field and a User-Agent.
 *
 * It is set from the same value the updater compares against, which is the point: a scrobble that reports
 * a version the updater would not recognise is worse than useless, because it looks correct.
 */
object AppVersion {

    /** What a build that does not know its own version reports, rather than a plausible-looking lie. */
    const val UNKNOWN: String = "0.0.0"

    @Volatile
    private var current: Version? = null

    /** Called once per process, by whichever application is starting. */
    fun set(version: Version?) {
        current = version
    }

    /** The version as a service wants it written. */
    val name: String get() = current?.toString() ?: UNKNOWN
}
