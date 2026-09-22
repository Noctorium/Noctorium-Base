package app.noctorium.update

/**
 * A version, in the order people mean rather than the order strings sort in.
 *
 * Written out rather than compared as text because the obvious way is wrong in a way that only shows up
 * later: "1.10.0" sorts before "1.9.0" alphabetically, so an updater built on string comparison works
 * perfectly for nine releases and then silently stops offering any.
 */
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** "beta.1" from 1.2.3-beta.1, or null for a plain release. */
    val preRelease: String? = null,
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }

        // 1.2.3 is newer than 1.2.3-beta.1: a pre-release comes before the release it leads to. Getting
        // this backwards would offer people a beta as an upgrade from the finished version.
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    /** The way it is written, which is what a release note or a settings screen should show. */
    override fun toString(): String =
        "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    private fun comparePreRelease(left: String, right: String): Int {
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val a = leftParts.getOrNull(index)
            val b = rightParts.getOrNull(index)
            // Fewer parts sorts first: beta comes before beta.1.
            if (a == null) return -1
            if (b == null) return 1
            val numbers = a.toIntOrNull() to b.toIntOrNull()
            val result = when {
                // Numeric parts compare as numbers, so beta.10 follows beta.9 rather than preceding it.
                numbers.first != null && numbers.second != null -> numbers.first!!.compareTo(numbers.second!!)
                // A number sorts before a word, which is what the semver ordering says.
                numbers.first != null -> -1
                numbers.second != null -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return 0
    }

    companion object {
        /**
         * Reads a version out of whatever it is written on.
         *
         * Tolerant of a leading v and of build metadata, because the same number arrives here from a git
         * tag, a release name and the application's own manifest, and each of those has its own habits.
         * Null when there is no version in it at all, which is a real answer and not a zero.
         */
        fun parse(text: String?): Version? {
            val trimmed = text?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
            if (trimmed.isEmpty()) return null
            // Build metadata after a + says nothing about ordering, so it is dropped rather than compared.
            val withoutBuild = trimmed.substringBefore('+')
            val preRelease = withoutBuild.substringAfter('-', "").takeIf(String::isNotBlank)
            val numbers = withoutBuild.substringBefore('-').split('.')
            val major = numbers.getOrNull(0)?.toIntOrNull() ?: return null
            return Version(
                major = major,
                minor = numbers.getOrNull(1)?.toIntOrNull() ?: 0,
                patch = numbers.getOrNull(2)?.toIntOrNull() ?: 0,
                preRelease = preRelease,
            )
        }
    }
}
