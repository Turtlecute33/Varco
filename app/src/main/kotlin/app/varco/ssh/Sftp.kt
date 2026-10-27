package app.varco.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import java.io.InputStream
import java.io.OutputStream

/**
 * SFTP on an open [SshConnection]. All calls block (network); run them off the main thread, one at a time
 * (a jsch SFTP channel is not thread-safe).
 */
class Sftp(connection: SshConnection) : AutoCloseable {
    class Entry(val name: String, val path: String, val isDir: Boolean, val isLink: Boolean, val size: Long, val modified: Long)

    private val channel = (connection.jsch.openChannel("sftp") as ChannelSftp).apply { connect(TIMEOUT_MS) }

    val home: String = channel.home

    val isOpen: Boolean get() = channel.isConnected && !channel.isClosed

    /** Folders first, then files; both by name. "." and ".." are left out. */
    fun list(path: String): List<Entry> {
        val found = ArrayList<ChannelSftp.LsEntry>()
        // Only collect here: jsch parses the listing and a stat with one shared buffer.
        channel.ls(lit(path)) { e ->
            if (e.filename != "." && e.filename != "..") found += e
            ChannelSftp.LsEntrySelector.CONTINUE
        }
        var stats = 0
        return found.map { e ->
            val a = e.attrs
            val full = join(path, e.filename)
            // A link to a folder opens like a folder. Each link costs a round trip, so only the first LINK_STATS
            // are checked here; the others show as files until a tap checks them (isDirLink).
            val isDir = a.isDir || (a.isLink && stats++ < LINK_STATS && isDirLink(full))
            Entry(e.filename, full, isDir, a.isLink, a.size, a.mTime * 1000L)
        }.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** True when the link at [path] goes to a folder. A broken link counts as a file. */
    fun isDirLink(path: String): Boolean = runCatching { channel.stat(lit(path)).isDir }.getOrDefault(false)

    fun download(path: String, out: OutputStream, progress: (Long) -> Unit) =
        channel.get(lit(path), out, Monitor(progress))

    /**
     * Replaces a file at [path]: check [exists] first, so that the user can decide. With posix-rename, the data
     * goes to a part file that then takes the place of the file in one step, so that a cut-off upload keeps the
     * old file. Else, or when the folder is not writable, the upload writes into the file itself.
     */
    fun upload(input: InputStream, path: String, progress: (Long) -> Unit) {
        if (channel.getExtension(POSIX_RENAME) != "1" || !uploadByRename(input, path, progress)) {
            channel.put(input, lit(path), Monitor(progress), ChannelSftp.OVERWRITE)
        }
    }

    /**
     * Uploads to ".<name>.varco-part" next to the file, copies the mode, and renames it over the file. A link
     * stays a link: its target gets the data. False, before any data is read, when this is not possible.
     */
    private fun uploadByRename(input: InputStream, path: String, progress: (Long) -> Unit): Boolean {
        val isLink = runCatching { channel.lstat(lit(path)).isLink }.getOrDefault(false)
        // realpath takes the raw path: it does no glob.
        val target = if (isLink) runCatching { channel.realpath(path) }.getOrNull() ?: return false else path
        val part = join(parent(target), ".${target.substringAfterLast('/')}.varco-part")
        // A part file left by a cut-off upload, or a link in its place: remove it, so that no data goes through a link.
        runCatching { channel.rm(lit(part)) }
        var sent = false
        try {
            channel.put(input, lit(part), Monitor { sent = true; progress(it) }, ChannelSftp.OVERWRITE)
            runCatching { channel.chmod(channel.stat(lit(target)).permissions and 0xfff, lit(part)) }
            channel.rename(lit(part), lit(target))
        } catch (e: Exception) {
            runCatching { channel.rm(lit(part)) }
            // No write access to the folder and no data read: the caller writes into the file itself.
            if (!sent && e is SftpException && e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) return false
            throw e
        }
        return true
    }

    /** True when [path] exists. A link counts, also a broken one: an upload writes through it. */
    fun exists(path: String): Boolean = try {
        channel.lstat(lit(path))
        true
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
    }

    fun mkdir(path: String) = channel.mkdir(path)

    fun rename(from: String, to: String) = channel.rename(lit(from), lit(to))

    /** Deletes a file, or an empty folder. */
    fun delete(entry: Entry) = if (entry.isDir && !entry.isLink) channel.rmdir(lit(entry.path)) else channel.rm(lit(entry.path))

    override fun close() = channel.disconnect()

    private class Monitor(private val progress: (Long) -> Unit) : SftpProgressMonitor {
        private var total = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
        override fun count(count: Long): Boolean {
            total += count
            progress(total)
            return true
        }
        override fun end() = Unit
    }

    companion object {
        private const val TIMEOUT_MS = 15_000

        /** Links that [list] checks for a folder target. */
        private const val LINK_STATS = 50

        /** The SFTP extension that renames over an existing file in one step. */
        private const val POSIX_RENAME = "posix-rename@openssh.com"

        fun join(dir: String, name: String) = if (dir.endsWith('/')) dir + name else "$dir/$name"

        fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

        /**
         * JSch reads * ? and \ in remote paths as glob syntax; escape them so that a name means only itself.
         * mkdir does not unquote, so it takes the raw path.
         */
        internal fun lit(path: String) = path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
    }
}
