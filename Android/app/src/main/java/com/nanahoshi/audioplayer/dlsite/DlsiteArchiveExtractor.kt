package com.nanahoshi.audioplayer.dlsite

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.BadRarArchiveException
import com.github.junrar.exception.CorruptHeaderException
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.NotRarArchiveException
import com.github.junrar.volume.FileVolume
import com.github.junrar.volume.VolumeManager
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32

data class DlsiteArchiveEntry(val relativePath: String, val isDirectory: Boolean, val size: Long)

internal data class DownloadArchiveContents(val entries: List<DlsiteArchiveEntry>, val skipped: Int)

internal fun extractDownloadArchive(
    archives: List<File>,
    destination: File,
    normalize: (String) -> String?,
    pathKey: (String) -> String,
    checkCancelled: () -> Unit,
): DownloadArchiveContents =
    DlsiteArchiveExtractor.extractContents(archives, destination, { _, _ -> }, checkCancelled, normalize, pathKey)

/** Extracts into an empty staging directory. Callers publish/associate files only after success. */
object DlsiteArchiveExtractor {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_DICTIONARY = 64L * 1024 * 1024

    fun extract(
        archives: List<File>,
        destination: File,
        onProgress: (Int, Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): List<DlsiteArchiveEntry> = extractContents(archives, destination, onProgress, checkCancelled).entries

    internal fun extractContents(
        archives: List<File>,
        destination: File,
        onProgress: (Int, Long) -> Unit,
        checkCancelled: () -> Unit,
        normalize: ((String) -> String?)? = null,
        pathKey: ((String) -> String)? = null,
    ): DownloadArchiveContents {
        if (archives.isEmpty() || archives.any { !it.isFile }) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_extract_incomplete))
        if (destination.exists() && (!destination.isDirectory || destination.listFiles()?.isNotEmpty() != false)) {
            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_extract_directory))
        }
        if (!destination.exists() && !destination.mkdirs()) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_create_extract_directory))
        val root = destination.canonicalFile
        val state = Extraction(root, checkCancelled, onProgress, normalize, pathKey)
        try {
            checkCancelled()
            if (archives.size == 1 && archives.first().extension.equals("zip", true)) {
                extractZip(archives.first(), state)
            } else {
                extractRar(archives, state)
            }
            checkCancelled()
            val entries = root.walkTopDown().drop(1).map { file ->
                checkCancelled()
                DlsiteArchiveEntry(file.relativeTo(root).invariantSeparatorsPath, file.isDirectory, if (file.isFile) file.length() else 0L)
            }.toList()
            return DownloadArchiveContents(entries, state.skipped)
        } catch (error: CancellationException) {
            destination.deleteRecursively()
            throw error
        } catch (error: DlsiteArchiveException) {
            destination.deleteRecursively()
            throw error
        } catch (error: Exception) {
            destination.deleteRecursively()
            checkCancelled()
            if (generateSequence<Throwable>(error) { it.cause }.take(8).any {
                it is java.util.zip.ZipException || it is CrcErrorException ||
                    it is CorruptHeaderException || it is BadRarArchiveException || it is NotRarArchiveException
            }) throw DlsiteArchiveIntegrityException(TextRef.Res(R.string.dlsite_archive_error_original_integrity))
            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_extract))
        }
    }

    private fun extractZip(archive: File, state: Extraction) {
        ZipFile.builder().setFile(archive).setCharset(Charset.forName("MS932"))
            .setUseUnicodeExtraFields(true).get().use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    state.checked()
                    val entry = entries.nextElement()
                    if (entry.isUnixSymlink || !zip.canReadEntryData(entry)) {
                        throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_unsupported_entry))
                    }
                    val file = state.entry(entry.name, entry.isDirectory) ?: continue
                    if (entry.isDirectory) continue
                    state.output(file, entry.size).use { output ->
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                state.checked()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        if (entry.crc >= 0 && output.crc.value != entry.crc) {
                            throw DlsiteArchiveIntegrityException(TextRef.Res(R.string.dlsite_archive_error_integrity))
                        }
                        output.verifySize()
                    }
                }
            }
    }

    private fun extractRar(archives: List<File>, state: Extraction) {
        // Use the resolved official volume order; never run SFX executables or guess adjacent files.
        val volumes = VolumeManager { archive, previous ->
            state.checked()
            val next = if (previous == null) 0 else archives.indexOf((previous as FileVolume).file) + 1
            archives.getOrNull(next)?.let { FileVolume(archive, it) }
        }
        val options = ArchiveOptions.builder().maxDictionarySize(MAX_DICTIONARY).build()
        Archive(volumes, options).use { rar ->
            while (true) {
                state.checked()
                val entry = rar.nextFileHeader() ?: break
                if (entry.isEncrypted) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_encrypted))
                if (entry.redirection != null || (entry.fileAttr and 0xF000) == 0xA000) {
                    throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_symlink))
                }
                val file = state.entry(entry.fileName, entry.isDirectory)
                if (entry.isDirectory) continue
                state.output(file, if (entry.isUnpSizeUnknown) -1 else entry.fullUnpackSize).use { output ->
                    rar.extractFile(entry, output)
                    output.verifySize()
                }
            }
        }
    }

    private class Extraction(
        val root: File,
        val checked: () -> Unit,
        val progress: (Int, Long) -> Unit,
        private val normalize: ((String) -> String?)?,
        private val pathKey: ((String) -> String)?,
    ) {
        private var files = 0
        private var bytes = 0L
        private var seen = 0
        var skipped = 0
            private set
        private val names = mutableSetOf<String>()
        private data class Path(val name: String, val directory: Boolean)
        private val paths = mutableMapOf<String, Path>()

        fun entry(raw: String, directory: Boolean): File? {
            checked()
            val safe = safeRelativePath(raw)
            if (++seen > MAX_ENTRIES) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_invalid_entries))
            val name = if (normalize != null && pathKey != null) {
                val normalized = normalize.invoke(safe) ?: throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_unsafe_path))
                val name = reserve(normalized, directory, pathKey)
                if (name == null) {
                    skipped++
                    return null
                }
                name
            } else {
                if (!names.add(safe)) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_invalid_entries))
                safe
            }
            val file = File(root, name)
            if (!file.canonicalPath.startsWith(root.path + File.separator)) {
                throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_extract_stopped))
            }
            if (directory) {
                if ((!file.isDirectory && !file.mkdirs()) || file.isFile) throw IOException("Directory unavailable")
            } else {
                if (file.exists()) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_entry_conflict))
                val parent = file.parentFile ?: throw IOException("Directory unavailable")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Directory unavailable")
            }
            return file
        }

        private fun reserve(name: String, directory: Boolean, keyOf: (String) -> String): String? {
            val parts = name.split('/')
            val key = keyOf(name)
            if (key in names) return null
            var current = ""
            for ((index, part) in parts.withIndex()) {
                val path = if (current.isEmpty()) part else "$current/$part"
                val folded = keyOf(path)
                val isDirectory = index < parts.lastIndex || directory
                val old = paths[folded]
                if (old != null && old.directory != isDirectory) return null
                current = old?.name ?: path
                paths.putIfAbsent(folded, Path(current, isDirectory))
            }
            names += key
            return current
        }

        fun output(file: File?, expected: Long): CheckedOutput {
            val stream = file?.outputStream() ?: object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
            }
            return CheckedOutput(stream, expected, root, checked,
                { count -> bytes += count; progress(files, bytes) }, { files++; progress(files, bytes) })
        }
    }

    private class CheckedOutput(
        stream: OutputStream,
        private val expected: Long,
        private val root: File,
        private val checked: () -> Unit,
        private val progress: (Int) -> Unit,
        private val completed: () -> Unit,
    ) : FilterOutputStream(stream) {
        val crc = CRC32()
        private var written = 0L
        private var untilSpaceCheck = 0L
        override fun write(value: Int) {
            val bytes = byteArrayOf(value.toByte())
            write(bytes, 0, 1)
        }
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            checked()
            if (count <= 0) return
            written += count
            if (expected >= 0 && written > expected) throw DlsiteArchiveIntegrityException(TextRef.Res(R.string.dlsite_archive_error_integrity))
            if (untilSpaceCheck <= 0) {
                if (root.usableSpace < count + 1024L * 1024) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_extract_space))
                untilSpaceCheck = 4L * 1024 * 1024
            }
            untilSpaceCheck -= count
            out.write(bytes, offset, count)
            progress(count)
            crc.update(bytes, offset, count)
        }
        fun verifySize() {
            if (expected >= 0 && written != expected) throw DlsiteArchiveIntegrityException(TextRef.Res(R.string.dlsite_archive_error_retrieve_incomplete))
            completed()
        }
    }

    internal fun safeRelativePath(raw: String): String {
        if (raw.any { it == '\u0000' || it.isISOControl() } || raw.startsWith('/') || raw.startsWith('\\')) {
            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_unsafe_path))
        }
        val name = raw.replace('\\', '/').trimEnd('/')
        val parts = name.split('/')
        if (name.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." || it.contains(':') }) {
            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_unsafe_path))
        }
        return name
    }
}
