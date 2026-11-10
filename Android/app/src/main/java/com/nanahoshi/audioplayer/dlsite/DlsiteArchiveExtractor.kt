package com.nanahoshi.audioplayer.dlsite

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

/** Extracts into an empty staging directory. Callers publish/associate files only after success. */
object DlsiteArchiveExtractor {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_DICTIONARY = 64L * 1024 * 1024

    fun extract(
        archives: List<File>,
        destination: File,
        checkCancelled: () -> Unit = {},
    ): List<DlsiteArchiveEntry> {
        if (archives.isEmpty() || archives.any { !it.isFile }) throw DlsiteArchiveException("压缩包文件不完整，请重试")
        if (destination.exists() && (!destination.isDirectory || destination.listFiles()?.isNotEmpty() != false)) {
            throw DlsiteArchiveException("解压目录不可用，请重试")
        }
        if (!destination.exists() && !destination.mkdirs()) throw DlsiteArchiveException("无法创建解压目录，请检查存储空间")
        val root = destination.canonicalFile
        val state = Extraction(root, checkCancelled)
        try {
            checkCancelled()
            if (archives.size == 1 && archives.first().extension.equals("zip", true)) {
                extractZip(archives.first(), state)
            } else {
                extractRar(archives, state)
            }
            checkCancelled()
            return root.walkTopDown().drop(1).map { file ->
                checkCancelled()
                DlsiteArchiveEntry(file.relativeTo(root).invariantSeparatorsPath, file.isDirectory, if (file.isFile) file.length() else 0L)
            }.toList()
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
            }) throw DlsiteArchiveIntegrityException("完整原始包校验失败，请重新下载")
            throw DlsiteArchiveException("完整原始包解压失败，文件可能损坏、缺少分卷或格式不受支持")
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
                        throw DlsiteArchiveException("压缩包里有不支持或已加密的文件")
                    }
                    val file = state.entry(entry.name, entry.isDirectory)
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
                            throw DlsiteArchiveIntegrityException("压缩包校验失败，请重新下载")
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
                if (entry.isEncrypted || entry.redirection != null || (entry.fileAttr and 0xF000) == 0xA000) {
                    throw DlsiteArchiveException("压缩包含有不支持的链接文件")
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

    private class Extraction(val root: File, val checked: () -> Unit) {
        private val names = mutableSetOf<String>()
        fun entry(raw: String, directory: Boolean): File {
            checked()
            val name = safeRelativePath(raw)
            if (names.size >= MAX_ENTRIES || !names.add(name)) {
                throw DlsiteArchiveException("压缩包内容异常，无法解压")
            }
            val file = File(root, name)
            if (!file.canonicalPath.startsWith(root.path + File.separator)) {
                throw DlsiteArchiveException("压缩包内容异常，已停止解压")
            }
            if (directory) {
                if ((!file.isDirectory && !file.mkdirs()) || file.isFile) throw IOException("Directory unavailable")
            } else {
                if (file.exists()) throw DlsiteArchiveException("压缩包内容冲突，无法解压")
                val parent = file.parentFile ?: throw IOException("Directory unavailable")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Directory unavailable")
            }
            return file
        }
        fun output(file: File, expected: Long): CheckedOutput = CheckedOutput(file.outputStream(), expected, root, checked)
    }

    private class CheckedOutput(
        stream: OutputStream,
        private val expected: Long,
        private val root: File,
        private val checked: () -> Unit,
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
            if (expected >= 0 && written > expected) throw DlsiteArchiveIntegrityException("压缩包校验失败，请重新下载")
            if (untilSpaceCheck <= 0) {
                if (root.usableSpace < count + 1024L * 1024) throw DlsiteArchiveException("存储空间不足，无法解压完整原始包")
                untilSpaceCheck = 4L * 1024 * 1024
            }
            untilSpaceCheck -= count
            out.write(bytes, offset, count)
            crc.update(bytes, offset, count)
        }
        fun verifySize() {
            if (expected >= 0 && written != expected) throw DlsiteArchiveIntegrityException("压缩包文件不完整，请重新下载")
        }
    }

    internal fun safeRelativePath(raw: String): String {
        if (raw.any { it == '\u0000' || it.isISOControl() } || raw.startsWith('/') || raw.startsWith('\\')) {
            throw DlsiteArchiveException("压缩包包含不安全的路径")
        }
        val name = raw.replace('\\', '/').trimEnd('/')
        val parts = name.split('/')
        if (name.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." || it.contains(':') }) {
            throw DlsiteArchiveException("压缩包包含不安全的路径")
        }
        return name
    }
}
