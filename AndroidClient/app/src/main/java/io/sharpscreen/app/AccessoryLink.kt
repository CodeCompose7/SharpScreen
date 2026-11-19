package io.sharpscreen.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * USB to the Mac without ADB (issue #3). The Mac switches the tablet into
 * Android Open Accessory mode; this keeps the accessory open and relays it to
 * a local TCP port, so StreamClient connects to 127.0.0.1:[port] exactly as it
 * does through adb reverse. Each StreamClient connection is one UsbLink
 * session.
 *
 * The accessory driver hands out at most 16 KB per read; reads always use a
 * 16 KB buffer (a smaller one can lose the rest of a packet).
 */
object AccessoryLink {
    private const val TAG = "AccessoryLink"
    private const val READ_SIZE = 16 * 1024

    /** Accessory strings the Mac sends (MacHost AccessoryLink). */
    const val MANUFACTURER = "SharpScreen"
    const val MODEL = "SharpScreen"

    enum class State { CLOSED, OPENING, READY }

    @Volatile var state = State.CLOSED
        private set

    /** Local relay port while open; StreamClient connects here. */
    @Volatile var port = 0
        private set

    /** Main-thread listeners for [state] changes. */
    private val listeners = mutableSetOf<(State) -> Unit>()

    private val lock = Any()
    private var fd: ParcelFileDescriptor? = null
    private var usbOut: OutputStream? = null
    private var server: ServerSocket? = null
    private var session: Socket? = null
    private var sessionId = 0
    private var detachReceiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    fun addListener(listener: (State) -> Unit) = synchronized(listeners) { listeners.add(listener) }

    fun removeListener(listener: (State) -> Unit) = synchronized(listeners) { listeners.remove(listener) }

    fun isOurs(accessory: UsbAccessory?) = accessory != null && accessory.manufacturer == MANUFACTURER && accessory.model == MODEL

    /** Our accessory, if the tablet is in accessory mode for a SharpScreen Mac. */
    fun attachedAccessory(context: Context): UsbAccessory? = context.getSystemService(UsbManager::class.java).accessoryList?.firstOrNull { isOurs(it) }

    fun accessoryFrom(intent: Intent?): UsbAccessory? {
        val accessory =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
        return accessory?.takeIf { isOurs(it) }
    }

    /** Opens [accessory] (permission already granted). No-op when already open. */
    fun open(
        context: Context,
        accessory: UsbAccessory,
    ): Boolean {
        synchronized(lock) {
            if (state != State.CLOSED) return true
            val usb = context.getSystemService(UsbManager::class.java)
            val pfd =
                try {
                    usb.openAccessory(accessory)
                } catch (e: SecurityException) {
                    Log.w(TAG, "openAccessory: no permission", e)
                    null
                } ?: return false
            // IPv4: StreamClient connects to 127.0.0.1 (getLoopbackAddress() can be ::1).
            val relay = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            fd = pfd
            usbOut = FileOutputStream(pfd.fileDescriptor)
            server = relay
            port = relay.localPort
            appContext = context.applicationContext
            val input = FileInputStream(pfd.fileDescriptor)
            Thread({ usbReadLoop(input) }, "AccessoryLink.usb").start()
            Thread({ acceptLoop(relay) }, "AccessoryLink.relay").start()
            registerDetach(context.applicationContext)
            setState(State.OPENING)
            Log.i(TAG, "accessory open, relay on 127.0.0.1:$port")
        }
        return true
    }

    fun close() {
        val oldFd: ParcelFileDescriptor?
        synchronized(lock) {
            if (state == State.CLOSED) return
            oldFd = fd
            fd = null
            usbOut = null
            closeQuietly(session)
            session = null
            closeQuietly(server)
            server = null
            port = 0
            detachReceiver?.let { r -> appContext?.let { runCatching { it.unregisterReceiver(r) } } }
            detachReceiver = null
        }
        closeQuietly(oldFd)
        setState(State.CLOSED)
        Log.i(TAG, "accessory closed")
    }

    private fun registerDetach(context: Context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) close()
                }
            }
        detachReceiver = receiver
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    // ---- USB -> local session ----

    private fun usbReadLoop(input: InputStream) {
        val buffer = ByteArray(READ_SIZE)
        val parser = UsbLink.Parser()
        // Off the main thread: the write waits until the Mac reads. The Mac
        // answers HELLO; until then the link is OPENING.
        if (!send(UsbLink.encode(UsbLink.HELLO, 0, byteArrayOf(UsbLink.VERSION.toByte())))) {
            close()
            return
        }
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                for (frame in parser.feed(buffer, n)) handle(frame)
            }
        } catch (e: IOException) {
            Log.i(TAG, "USB read ended: ${e.message}")
        }
        close()
    }

    private fun handle(frame: UsbLink.Frame) {
        when (frame.type) {
            UsbLink.HELLO -> if (state == State.OPENING) setState(State.READY)
            UsbLink.DATA -> {
                val target = synchronized(lock) { session.takeIf { sessionId == frame.session } } ?: return
                try {
                    target.getOutputStream().write(frame.payload)
                } catch (e: IOException) {
                    endSession(frame.session, notifyMac = true)
                }
            }
            UsbLink.CLOSE -> endSession(frame.session, notifyMac = false)
        }
    }

    // ---- local session -> USB ----

    private fun acceptLoop(relay: ServerSocket) {
        while (!relay.isClosed) {
            val socket =
                try {
                    relay.accept()
                } catch (e: IOException) {
                    break
                }
            socket.tcpNoDelay = true
            val id: Int
            synchronized(lock) {
                closeQuietly(session)
                sessionId = (sessionId + 1) and 0xFF
                id = sessionId
                session = socket
            }
            if (!send(UsbLink.encode(UsbLink.OPEN, id))) {
                closeQuietly(socket)
                continue
            }
            Thread({ sessionReadLoop(socket, id) }, "AccessoryLink.session").start()
        }
    }

    private fun sessionReadLoop(
        socket: Socket,
        id: Int,
    ) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = socket.getInputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0 && !send(UsbLink.encode(UsbLink.DATA, id, buffer, 0, n))) break
            }
        } catch (_: IOException) {
        }
        endSession(id, notifyMac = true)
    }

    private fun endSession(
        id: Int,
        notifyMac: Boolean,
    ) {
        val old =
            synchronized(lock) {
                if (sessionId != id || session == null) return
                session.also { session = null }
            }
        closeQuietly(old)
        if (notifyMac) send(UsbLink.encode(UsbLink.CLOSE, id))
    }

    private val writeLock = Any()

    private fun send(frame: ByteArray): Boolean {
        val out = synchronized(lock) { usbOut } ?: return false
        return try {
            synchronized(writeLock) {
                var offset = 0
                for (size in UsbLink.writeSizes(frame.size)) {
                    out.write(frame, offset, size)
                    offset += size
                }
            }
            true
        } catch (e: IOException) {
            Log.i(TAG, "USB write failed: ${e.message}")
            false
        }
    }

    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        val snapshot = synchronized(listeners) { listeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post { snapshot.forEach { it(newState) } }
    }

    private fun closeQuietly(c: java.io.Closeable?) {
        try {
            c?.close()
        } catch (_: IOException) {
        }
    }
}
