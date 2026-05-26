package link.oppolink.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.IOException

/**
 * Thin wrapper around an open L2CAP CoC [BluetoothSocket].
 *
 * **Concurrency contract** (Sprint 2 D6): a single instance is safe to share
 * between **exactly one** sender thread and **exactly one** receiver thread.
 * `BluetoothSocket.inputStream` and `outputStream` are independent OS-level
 * handles, so [send] (write to outputStream) and [receiveExact] (read from
 * inputStream) cannot interfere with each other. **Do not** call [send] from
 * two threads, or [receiveExact] from two threads - that would corrupt the
 * stream because we don't lock at the wrapper level.
 *
 * I/O is intentionally blocking: `BluetoothSocket`'s streams are blocking by
 * design and coroutine dispatcher jitter is not tolerable on the 20 ms audio
 * tick. Use this from dedicated [Thread]s at audio-grade priority.
 */
class L2capChannel(
    private val socket: BluetoothSocket,
) : Closeable {

    private val input = socket.inputStream
    private val output = socket.outputStream

    /** Maximum transmission unit reported by the kernel for this socket. */
    val maxTransmitUnit: Int = socket.maxTransmitPacketSize

    /** Maximum receive unit reported by the kernel for this socket. */
    val maxReceiveUnit: Int = socket.maxReceivePacketSize

    /**
     * Write [payload] to the socket and flush. Throws [IOException] when the
     * peer has gone away or the socket has been closed underneath us.
     */
    fun send(payload: ByteArray) {
        output.write(payload)
        output.flush()
    }

    /**
     * Fill [buffer] with exactly `buffer.size` bytes. Returns the number of
     * bytes read (== `buffer.size` on success). Throws [IOException] if the
     * stream closes before the buffer is full.
     */
    fun receiveExact(buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) throw IOException("L2CAP channel closed before $read/${buffer.size} bytes")
            read += n
        }
        return read
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }
}
