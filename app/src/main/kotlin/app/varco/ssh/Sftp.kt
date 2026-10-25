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
        channel.ls(path) { e ->
            if (e.filename != "." && e.filename != "..") found += e
            ChannelSftp.LsEntrySelector.CONTINUE
        }
        return found.map { e ->
            val a = e.attrs
            val full = join(path, e.filename)
            // A link to a folder opens like a folder.
            val isDir = a.isDir || (a.isLink && runCatching { channel.stat(full).isDir }.getOrDefault(false))
            Entry(e.filename, full, isDir, a.isLink, a.size, a.mTime * 1000L)
        }.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
    }

    fun download(path: String, out: OutputStream, progress: (Long) -> Unit) =
        channel.get(path, out, Monitor(progress))

    /** Replaces a file at [path]: check [exists] first, so that the user can decide. */
    fun upload(input: InputStream, path: String, progress: (Long) -> Unit) =
        channel.put(input, path, Monitor(progress), ChannelSftp.OVERWRITE)

    /** True when [path] exists. A link counts, also a broken one: an upload writes through it. */
    fun exists(path: String): Boolean = try {
        channel.lstat(path)
        true
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
    }

    fun mkdir(path: String) = channel.mkdir(path)

    fun rename(from: String, to: String) = channel.rename(from, to)

    /** Deletes a file, or an empty folder. */
    fun delete(entry: Entry) = if (entry.isDir && !entry.isLink) channel.rmdir(entry.path) else channel.rm(entry.path)

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

        fun join(dir: String, name: String) = if (dir.endsWith('/')) dir + name else "$dir/$name"

        fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    }
}
