package app.noctorium.net

/** What a browser on this device answered. */
data class BrowserReply(val status: Int, val body: String) {
    companion object {
        /**
         * The request was handed to the page and no answer came back in time.
         *
         * Not the same as no browser at all, which is null: this request may well have reached the service and
         * been carried out -- a like that SoundCloud kept while the page was still waiting -- so it is neither a
         * success nor a failure, and sending it again by another road is not the answer either. A like sent
         * again that way met the bot protection, was reported as failed, and its heart was taken back from a
         * track that SoundCloud had in fact liked.
         */
        const val UNANSWERED = -1

        fun unanswered() = BrowserReply(UNANSWERED, "")
    }
}

/**
 * Makes a request from inside a real browser on this device, for the endpoints that will take nothing else.
 *
 * SoundCloud's API is read by anything that asks. Writing to it is not: a like, or a new playlist, is
 * answered 403 with a captcha page from their bot protection, whatever the request carries. That refusal
 * is not about the account -- the session is valid and the same one reads likes perfectly -- and it is not
 * about the headers either. Sending Chrome's user agent, its client hints, its fetch metadata and the
 * clearance cookie the browser had already earned all came back with the same captcha, from the phone and
 * from a plain command line alike. What is being judged is the client itself, below the request.
 *
 * So the write is handed to a browser that will be judged favourably, because it genuinely is one. The
 * phone already has a Chromium that signed the listener in and holds the clearance it earned; asking it
 * to make the call is not a trick, it is the same browser making the same request the website makes.
 *
 * Returning null means no browser could be asked -- there is none on this platform, or it failed to run
 * the request -- and the caller falls back to an ordinary one. A reply, including a refusal, is an answer
 * and is used as it stands.
 */
fun interface BrowserRequester {
    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        /** JSON for the calls that carry one — creating a playlist, or saving its order. */
        body: String?,
    ): BrowserReply?
}
