package com.remotephone.agent.files

import android.os.Environment
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long
)

data class FileListResponse(
    val type: String = "file_list_response",
    val currentPath: String,
    val files: List<FileItem>
)

data class FileDownloadChunkResponse(
    val type: String = "file_download_chunk",
    val path: String,
    val offset: Long,
    val totalSize: Long,
    val dataBase64: String,
    val isLast: Boolean
)

object FileManager {
    private val gson = Gson()

    fun getDefaultPath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun listDirectoryJson(path: String?): String {
        val targetPath = if (path.isNullOrEmpty()) getDefaultPath() else path
        val dir = File(targetPath)
        val list = mutableListOf<FileItem>()

        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles()
            if (files != null) {
                // Directories first, then alphabetically
                val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                for (f in sorted) {
                    list.add(
                        FileItem(
                            name = f.name,
                            path = f.absolutePath,
                            isDir = f.isDirectory,
                            size = if (f.isDirectory) 0 else f.length(),
                            modified = f.lastModified()
                        )
                    )
                }
            }
        }

        return gson.toJson(FileListResponse(currentPath = targetPath, files = list))
    }

    fun readFileChunk(path: String, offset: Long, chunkSize: Int = 32 * 1024): FileDownloadChunkResponse? {
        val file = File(path)
        if (!file.exists() || file.isDirectory) return null

        val totalSize = file.length()
        val remaining = totalSize - offset
        if (remaining <= 0) {
            return FileDownloadChunkResponse(
                path = path,
                offset = offset,
                totalSize = totalSize,
                dataBase64 = "",
                isLast = true
            )
        }

        val bytesToRead = minOf(chunkSize.toLong(), remaining).toInt()
        val buffer = ByteArray(bytesToRead)

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buffer)
        }

        val base64 = android.util.Base64.encodeToString(buffer, android.util.Base64.NO_WRAP)
        val isLast = (offset + bytesToRead) >= totalSize

        return FileDownloadChunkResponse(
            path = path,
            offset = offset,
            totalSize = totalSize,
            dataBase64 = base64,
            isLast = isLast
        )
    }

    fun writeFileChunk(path: String, offset: Long, base64Data: String): Boolean {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()

            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(offset)
                raf.write(bytes)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun deleteFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
