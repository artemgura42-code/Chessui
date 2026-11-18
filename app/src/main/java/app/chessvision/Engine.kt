package app.chessvision

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import kotlin.math.min

/** UCI client for the official Stockfish Android ARM64 executable packaged as libstockfish.so. */
class Engine(ctx: Context) {
    private val executable = File(ctx.applicationInfo.nativeLibraryDir, "libstockfish.so")
    private val process: Process
    private val writer: BufferedWriter
    private val reader: BufferedReader

    init {
        if (!executable.isFile || !executable.canExecute()) {
            throw IOException("Stockfish не найден или не исполняется: ${executable.absolutePath}")
        }
        process = ProcessBuilder(executable.absolutePath).redirectErrorStream(true).start()
        writer = process.outputStream.bufferedWriter()
        reader = process.inputStream.bufferedReader()
        send("uci")
        readUntil("uciok")
        send("setoption name Threads value ${min(2, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)}")
        send("setoption name Hash value 64")
        send("isready")
        readUntil("readyok")
    }

    private fun send(command: String) {
        writer.write(command)
        writer.newLine()
        writer.flush()
    }

    private fun readUntil(expected: String) {
        while (true) {
            val line = reader.readLine() ?: throw IOException("Stockfish завершился до ответа $expected")
            if (line.startsWith("info string CRITICAL ERROR")) throw IOException("Stockfish отклонил команду: $line")
            if (line.startsWith(expected)) return
        }
    }

    @Synchronized
    fun best(fen: String, ms: Int = 1200): String? {
        send("position fen $fen")
        send("go movetime ${ms.coerceIn(300, 5000)}")
        while (true) {
            val line = reader.readLine() ?: throw IOException("Stockfish завершился во время анализа")
            if (line.startsWith("info string CRITICAL ERROR")) throw IOException("Некорректная позиция FEN: $line")
            if (line.startsWith("bestmove")) {
                val move = line.split(Regex("\\s+")).getOrNull(1) ?: return null
                return move.takeIf { it.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) }
            }
        }
    }

    fun close() {
        try { send("quit") } catch (_: Exception) { }
        try { process.destroy() } catch (_: Exception) { }
    }
}
