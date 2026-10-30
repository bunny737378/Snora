package arc.snora.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class FFmpegStreamServer(
    private val context: Context,
    private val ffmpegFile: File,
    private val sourcePath: String,
    private val startSec: Long = 0L,
    private val forceTranscodeVideo: Boolean = false
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var activeProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var port: Int = 0
        private set

    val streamUrl: String
        get() = "http://127.0.0.1:$port/stream"

    fun start(): String {
        stop()
        val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort

        serverJob = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch {
                        handleClient(client)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
        return streamUrl
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val reader = input.bufferedReader()
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                line = reader.readLine()
            }

            val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Connection: close\r\n" +
                    "Accept-Ranges: none\r\n\r\n"
            output.write(responseHeaders.toByteArray(Charsets.UTF_8))
            output.flush()

            streamFFmpeg(output)
        } catch (e: Exception) {
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun streamFFmpeg(output: OutputStream) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val cmd = mutableListOf(
            ffmpegFile.absolutePath,
            "-v", "error"
        )

        if (startSec > 0) {
            cmd.add("-ss")
            cmd.add(startSec.toString())
        }

        cmd.add("-i")
        cmd.add(sourcePath)

        if (forceTranscodeVideo) {
            cmd.add("-c:v")
            cmd.add("libx264")
            cmd.add("-preset")
            cmd.add("ultrafast")
            cmd.add("-crf")
            cmd.add("23")
        } else {
            cmd.add("-c:v")
            cmd.add("copy")
        }

        cmd.add("-c:a")
        cmd.add("aac")
        cmd.add("-b:a")
        cmd.add("192k")
        cmd.add("-f")
        cmd.add("mp4")
        cmd.add("-movflags")
        cmd.add("frag_keyframe+empty_moov+default_base_moof")
        cmd.add("pipe:1")

        val builder = ProcessBuilder(cmd)
        builder.environment()["LD_LIBRARY_PATH"] = nativeDir

        val process = builder.start()
        activeProcess = process

        val streamIn: InputStream = process.inputStream
        val buffer = ByteArray(32 * 1024)
        var bytesRead: Int

        try {
            while (streamIn.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: Exception) {
        } finally {
            process.destroy()
            activeProcess = null
        }
    }

    fun stop() {
        activeProcess?.destroy()
        activeProcess = null
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        serverSocket = null
    }
}
