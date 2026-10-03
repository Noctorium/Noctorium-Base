package app.noctorium.playback

import app.noctorium.platform.CommandRunner
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * mpv on a Mac, which is an application bundle rather than one program file.
 *
 * The program is `mpv.app/Contents/MacOS/mpv`, and it does not stand on its own: it loads LuaJIT from
 * `Contents/MacOS/lib` and MoltenVK from `Contents/Frameworks`, by paths relative to itself. A copy of the
 * binary taken out of its bundle -- which is all Windows needs of shinchiro's folder -- fails to start
 * looking for them. So on a Mac the tools folder holds the bundle whole, as `bin/mpv.app`, and the program
 * is found inside it.
 *
 * Everything here is apart from the network so it can be tested on a machine that is not a Mac: the two
 * programs it runs, `ditto` and `xattr`, are reached through a [CommandRunner] a test can stand in for.
 */
internal object MacMpvBundle {
    /** The bundle's name in the tools folder, and in the archive. */
    const val BUNDLE = "mpv.app"

    /** Unpacks a zip keeping permissions and links, which Java's own zip reading does not; every Mac has it. */
    const val DITTO = "/usr/bin/ditto"

    /** Edits extended attributes, which is where macOS keeps the quarantine flag. */
    const val XATTR = "/usr/bin/xattr"

    /** The attribute Gatekeeper reads to decide a program came from the internet and should be checked. */
    const val QUARANTINE = "com.apple.quarantine"

    private const val UNPACK_TIMEOUT_SECONDS = 300L
    private const val XATTR_TIMEOUT_SECONDS = 60L

    /** The program inside a bundle. */
    fun programIn(bundle: Path): Path = bundle.resolve("Contents").resolve("MacOS").resolve("mpv")

    /** The program of the bundle kept in [folder]: `folder/mpv.app/Contents/MacOS/mpv`. */
    fun executableIn(folder: Path): Path = programIn(folder.resolve(BUNDLE))

    /**
     * Unpacks the downloaded [archive] into [unpackInto] and puts the mpv bundle in it into [bin].
     *
     * Returns null when it worked, and a sentence worth showing when it did not.
     */
    fun install(archive: Path, unpackInto: Path, bin: Path, commands: CommandRunner): String? {
        Files.createDirectories(unpackInto)
        unpack(archive, unpackInto, commands)?.let { return it }
        // The archive also holds a macos_config folder of mpv settings. Noctorium starts mpv with
        // --no-config, so that is left behind with the rest of the temporary folder.
        val bundle = findBundle(unpackInto) ?: return "mpv was downloaded but the archive had no $BUNDLE in it."
        return replace(bundle, bin.resolve(BUNDLE)) { staged -> prepare(staged, commands) }
    }

    /**
     * Hands the zip to `ditto`.
     *
     * Not java.util.zip: it knows nothing of Unix permissions or symbolic links, so mpv would come out of it
     * unable to run, and any framework inside the bundle would lose the links its layout depends on.
     */
    private fun unpack(archive: Path, into: Path, commands: CommandRunner): String? {
        val result = runCatching {
            commands.run(listOf(DITTO, "-x", "-k", archive.toString(), into.toString()), null, UNPACK_TIMEOUT_SECONDS)
        }.getOrElse { return "The download could not be unpacked: ${it.message}" }
        return if (result.succeeded) null else "The download could not be unpacked."
    }

    /**
     * The mpv bundle somewhere in what was unpacked, as long as it has the program in it.
     *
     * Looked for rather than assumed to be at `mpv/mpv.app`, where today's archives keep it, so that a
     * change in how the archive is laid out costs nothing, while an archive with no program in it is still
     * refused rather than installed.
     */
    fun findBundle(root: Path): Path? = runCatching {
        Files.walk(root, BUNDLE_SEARCH_DEPTH).use { paths ->
            paths.filter { it.fileName?.toString() == BUNDLE && Files.isDirectory(it) && Files.isRegularFile(programIn(it)) }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()

    /** Makes the copy that is about to go into place ready to run, before anything can run it. */
    private fun prepare(staged: Path, commands: CommandRunner) {
        // ditto keeps the permissions the zip recorded, but nothing here is worth staking playback on that.
        runCatching { programIn(staged).toFile().setExecutable(true, false) }
        // A program that arrived from the internet can carry the quarantine flag, and Gatekeeper answers
        // it with a dialog -- which nobody sees for a program started with no window, so mpv would simply
        // never start. The download is not made by anything that sets the flag, so it is usually not
        // there; the attempt is cheap, and a failure (nothing to remove) is no different from success.
        runCatching { commands.run(listOf(XATTR, "-dr", QUARANTINE, staged.toString()), null, XATTR_TIMEOUT_SECONDS) }
    }

    /**
     * Puts a copy of [bundle] at [destination], replacing whatever is there, without ever leaving half a one.
     *
     * The copy is made beside the destination under another name, so a copy that fails part of the way
     * through -- a full disk, say -- leaves the old mpv exactly as it was. Only once the copy is whole does
     * it trade places with the old one, and a move within one folder is a rename: it happens or it does not.
     * Should the second rename fail, the old bundle is put back rather than leaving no mpv at all.
     *
     * [prepare] is given the finished copy before it goes into place. [copy] is [copyTree] everywhere but in
     * a test, which needs a copy that fails part of the way through on demand.
     */
    fun replace(
        bundle: Path,
        destination: Path,
        copy: (Path, Path) -> Unit = ::copyTree,
        prepare: (Path) -> Unit = {},
    ): String? {
        val staging = destination.resolveSibling(".${destination.fileName}.partial")
        val previous = destination.resolveSibling(".${destination.fileName}.old")
        runCatching {
            deleteTree(staging)
            deleteTree(previous)
            copy(bundle, staging)
        }.onFailure { error ->
            runCatching { deleteTree(staging) }
            return "Could not copy mpv into place: ${error.message ?: "the copy did not finish"}."
        }
        prepare(staging)

        val hadOne = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        if (hadOne) {
            runCatching { Files.move(destination, previous) }.onFailure {
                runCatching { deleteTree(staging) }
                return "Could not replace the mpv that is there. Stop playback and try again."
            }
        }
        runCatching { Files.move(staging, destination) }.onFailure {
            if (hadOne) runCatching { Files.move(previous, destination) }
            runCatching { deleteTree(staging) }
            return "Could not put mpv in place. Stop playback and try again."
        }
        runCatching { deleteTree(previous) }
        return null
    }

    /**
     * Copies a folder and everything in it, keeping each file's permissions and copying links as links.
     *
     * Links are not followed in either direction: a bundle that links to a folder outside itself must not
     * have that folder's contents poured into the copy.
     */
    fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    // Created plainly rather than with the source's attributes: a folder copied as read-only
                    // could not then have its own contents copied into it.
                    Files.createDirectories(target.resolve(source.relativize(directory).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.copy(
                        file,
                        target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * Deletes a folder and everything in it, or a single file, removing links rather than what they point to.
     *
     * Kotlin's File.deleteRecursively follows a link to a folder and empties the folder it leads to, which
     * for a bundle that links outside itself would reach well beyond anything Noctorium owns.
     */
    fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Today's archives keep the bundle at `mpv/mpv.app`, two levels down; one more is room to spare. */
    private const val BUNDLE_SEARCH_DEPTH = 3
}
