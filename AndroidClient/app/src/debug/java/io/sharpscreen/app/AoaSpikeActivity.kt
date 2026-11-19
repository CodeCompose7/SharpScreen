package io.sharpscreen.app

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-only experiment for issue #3: can the tablet talk to the Mac over USB
 * without ADB, using Android Open Accessory mode? The Mac side
 * (scripts/aoa_spike.c) switches the tablet into accessory mode and drives
 * these tests over the accessory's bulk endpoints:
 *
 *  - SINK(total): the Mac writes `total` bytes; reply with the ns it took here.
 *  - SOURCE(total): write `total` bytes to the Mac in 16 KB writes.
 *  - ECHO(count, size): read `size` bytes and send them back, `count` times.
 *
 * Command: "AOAS", u32 cmd, u64 arg1, u32 arg2, little endian (20 bytes).
 * Results go to logcat (tag AoaSpike) and the screen.
 */
class AoaSpikeActivity : Activity() {
    private lateinit var status: TextView
    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(48, 48, 48, 48)
            text = "AOA spike: waiting for accessory"
        }
        setContentView(status)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        val usb = getSystemService(UsbManager::class.java)
        val fromIntent =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
        val accessory = fromIntent ?: usb.accessoryList?.firstOrNull()
        if (accessory == null) {
            show("AOA spike: no accessory")
            return
        }
        if (!usb.hasPermission(accessory)) {
            show("AOA spike: no permission for $accessory")
            return
        }
        if (worker?.isAlive == true) return
        val fd = usb.openAccessory(accessory)
        if (fd == null) {
            show("AOA spike: openAccessory failed")
            return
        }
        pfd = fd
        show("AOA spike: opened ${accessory.manufacturer} / ${accessory.model}")
        worker = Thread({ serve(fd) }, "AoaSpike").also { it.start() }
    }

    private fun serve(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(BUFFER)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n < HEADER) {
                    log("short command: $n bytes")
                    continue
                }
                val h = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                if (h.int != MAGIC) {
                    log("bad magic, $n bytes")
                    continue
                }
                val cmd = h.int
                val arg1 = h.long
                val arg2 = h.int
                when (cmd) {
                    1 -> sink(input, output, buf, arg1)
                    2 -> source(output, arg1)
                    3 -> echo(input, output, buf, arg1.toInt(), arg2)
                    else -> log("unknown command $cmd")
                }
            }
        } catch (e: IOException) {
            log("I/O ended: $e")
        } finally {
            close()
        }
    }

    private fun sink(input: FileInputStream, output: FileOutputStream, buf: ByteArray, total: Long) {
        var got = 0L
        var reads = 0
        val start = SystemClock.elapsedRealtimeNanos()
        while (got < total) {
            val n = input.read(buf)
            if (n < 0) throw IOException("EOF after $got bytes")
            got += n
            reads++
        }
        val ns = SystemClock.elapsedRealtimeNanos() - start
        output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(ns).array())
        log("SINK %d MB in %.1f ms = %.1f MB/s (%d reads, avg %d B)".format(
            total shr 20, ns / 1e6, total / (ns / 1e9) / 1e6, reads, got / reads))
    }

    private fun source(output: FileOutputStream, total: Long) {
        val chunk = ByteArray(BUFFER) { it.toByte() }
        var sent = 0L
        val start = SystemClock.elapsedRealtimeNanos()
        while (sent < total) {
            val n = minOf(chunk.size.toLong(), total - sent).toInt()
            output.write(chunk, 0, n)
            sent += n
        }
        val ns = SystemClock.elapsedRealtimeNanos() - start
        log("SOURCE %d MB in %.1f ms = %.1f MB/s".format(total shr 20, ns / 1e6, total / (ns / 1e9) / 1e6))
    }

    private fun echo(input: FileInputStream, output: FileOutputStream, buf: ByteArray, count: Int, size: Int) {
        val packet = ByteArray(size)
        repeat(count) {
            var got = 0
            while (got < size) {
                val n = input.read(buf)
                if (n < 0) throw IOException("EOF in echo")
                System.arraycopy(buf, 0, packet, got, minOf(n, size - got))
                got += n
            }
            output.write(packet)
        }
        log("ECHO done: $count x $size B")
    }

    private fun close() {
        try {
            pfd?.close()
        } catch (_: IOException) {
        }
        pfd = null
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        show(message)
    }

    private fun show(message: String) {
        runOnUiThread { status.text = message }
    }

    private companion object {
        const val TAG = "AoaSpike"
        const val BUFFER = 16 * 1024 // f_accessory moves at most 16 KB per read
        const val HEADER = 20
        const val MAGIC = 0x53414F41 // "AOAS" little endian
    }
}
