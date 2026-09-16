package app.spiceity.playback

import app.spiceity.domain.ProviderType
import java.nio.file.Path

enum class AccountProbeOutcome {
    /** Cookies were read and the provider served content that only a signed-in account can see. */
    SIGNED_IN,

    /** Cookies were read and accepted, but the provider offers no cheap way to confirm the account. */
    COOKIES_READY,

    /** Cookies were read, yet the provider still treats the request as anonymous. */
    NOT_SIGNED_IN,

    /** The cookies could not be read at all — locked database, encryption, wrong profile, bad file. */
    COOKIES_UNREADABLE,

    BACKEND_MISSING,
    FAILED,
}

data class AccountProbeResult(
    val outcome: AccountProbeOutcome,
    val detail: String,
    val hint: String? = null,
    val cookieCount: Int? = null,
) {
    val usable: Boolean get() = outcome == AccountProbeOutcome.SIGNED_IN || outcome == AccountProbeOutcome.COOKIES_READY
}

data class AccountProbeRequest(
    val provider: ProviderType,
    val cookieArguments: List<String>,
    val sourceLabel: String,
    val browserProcessName: String? = null,
    val chromiumBrowser: Boolean = false,
    val cookieFile: Path? = null,
    val profileNamed: Boolean = false,
)

/**
 * Whether a saved session actually works, asked by using it.
 *
 * The whole point is that presence is not proof. A cookie sits in a jar looking identical whether it is live
 * or expired, and the sign-in loop this project already fixed once came from believing the jar: sign-in
 * reported success, wrote dead cookies, the service answered 401, and pressing sign-in again harvested the
 * same dead cookie and reported success again. Nothing failed anywhere in that circle, which is why it could
 * go round forever. So a probe makes a real request.
 *
 * An interface because how you make that request is not the same on both platforms — the desktop runs
 * yt-dlp against a feed only a signed-in account is served, while a phone has its own session and its own
 * way of asking.
 */
interface SessionProbe {
    suspend fun probe(request: AccountProbeRequest): AccountProbeResult
}

/**
 * For a platform that cannot check.
 *
 * Reports the session as ready rather than as broken, which is the honest answer: nothing has been shown to
 * be wrong with it. Claiming otherwise would put a warning on a perfectly good account.
 */
object UncheckedSession : SessionProbe {
    override suspend fun probe(request: AccountProbeRequest) = AccountProbeResult(
        outcome = AccountProbeOutcome.COOKIES_READY,
        detail = "Saved. Spiceity cannot verify a session on this device, so it is used as it is.",
    )
}
