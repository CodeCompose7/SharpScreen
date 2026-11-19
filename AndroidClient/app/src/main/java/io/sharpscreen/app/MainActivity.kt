package io.sharpscreen.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.usb.UsbManager
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.sharpscreen.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

private fun mainDiag(msg: String) = DiagLog.log("MA", msg)

// Also linked from SettingsActivity's About section.
internal const val GITHUB_URL = "https://github.com/CodeCompose7/SharpScreen"
internal const val MAC_APP_URL = "$GITHUB_URL/releases"
internal const val REPORT_PROBLEM_URL = "$GITHUB_URL/issues"

class MainActivity : AppCompatActivity() {
    private lateinit var wirelessController: WirelessTabController
    private lateinit var appUpdater: AppUpdater
    private lateinit var reviewPrompter: ReviewPrompter
    private val pairedHostStorage by lazy { PairedHostStorage(this) }
    private val cameraPerm by lazy { CameraPermissionManager(this) }
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private var videoDecoder: VideoDecoder? = null
    private var streamClient: StreamClient? = null

    /** In-flight code-pairing attempt (issue #35); cancelled when its dialog closes. */
    private var pairingJob: Job? = null
    private var currentSurfaceHolder: SurfaceHolder? = null
    private var currentTextureSurface: Surface? = null
    private var decoderUsingTextureView = false

    // Logical desktop behind the stream, 0 when the Mac predates desktopGeometry.
    // Shown next to the stream size; never used to size the decoder.
    private var desktopWidth = 0
    private var desktopHeight = 0

    private var displayWidth = 0 // 0 = no config received yet
    private var displayHeight = 0 // 0 = no config received yet
    private var displayRotation = 0 // 0, 90, 180, 270 degrees
    private var displayFlipHorizontal = false
    private var displayFlipVertical = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pingJob: kotlinx.coroutines.Job? = null

    // For dragging stats overlay
    private var isDraggingOverlay = false
    private var overlayDx = 0f
    private var overlayDy = 0f

    // Input prediction for low-latency gaming
    private val inputPredictor = InputPredictor()

    // Checklist status handler
    private val checklistHandler = Handler(Looper.getMainLooper())
    private var checklistRunnable: Runnable? = null
    private var isConnected = false // Track connection state to prevent checklist conflicts
        set(value) {
            field = value
            applyRefreshRatePreference()
            onStreamingUiChanged()
        }

    // Demo mode: plays a bundled clip so the app can be tried without a Mac
    private var demoPlayer: DemoPlayer? = null
    private var demoActive = false
        set(value) {
            field = value
            applyRefreshRatePreference()
            onStreamingUiChanged()
        }
    private var demoTouchHintShown = false

    /** True while video is on screen — a real session or the demo. Drives the streaming chrome. */
    private val isStreamingUi get() = isConnected || demoActive

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagLog.init(applicationContext)
        prefs = PreferencesManager(this)
        SelfUpdater.init(this)

        // Allow rotation based on device sensor when not connected
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Diagnostics: `adb shell am start -n io.sharpscreen.app.debug/io.sharpscreen.app.MainActivity
        // --ez diag.glCompositor true|false` switches the M1a GL compositor and remembers it.
        val diagPrefs = getSharedPreferences("diag", MODE_PRIVATE)
        intent?.takeIf { it.hasExtra("diag.glCompositor") }?.let {
            diagPrefs.edit().putBoolean("glCompositor", it.getBooleanExtra("diag.glCompositor", false)).apply()
        }
        GlCompositor.enabled = diagPrefs.getBoolean("glCompositor", true)
        // Per launch only, never remembered: a remembered showTiles=false once left
        // the tablet showing video while the host believed its tiles were on screen.
        // `--ez diag.tileTint true` tints lossless tiles green; `--ez diag.showTiles false`
        // keeps showing video (HEVC-only baseline); `--ez diag.lossless false` never
        // asks the host for tiles at all.
        diagPrefs.edit().remove("tileTint").remove("showTiles").remove("releaseTs").apply()
        GlCompositor.tintTiles = intent?.getBooleanExtra("diag.tileTint", false) ?: false
        GlCompositor.showTiles = intent?.getBooleanExtra("diag.showTiles", true) ?: true
        GlCompositor.losslessTiles = intent?.getBooleanExtra("diag.lossless", true) ?: true
        // `--ez diag.vsyncPresent false`: present frames as soon as drawn (before M4).
        GlCompositor.vsyncPresent = intent?.getBooleanExtra("diag.vsyncPresent", true) ?: true
        intent?.getIntExtra("diag.presentPercentile", 0)?.takeIf { it in 1..99 }?.let { PresentScheduler.PERCENTILE = it }
        // `--es diag.updateFeedURL http://127.0.0.1:8766/android-update.json`: read updates from
        // a test feed instead of the GitHub Release (this launch only).
        SelfUpdater.feedUrlOverride = intent?.getStringExtra("diag.updateFeedURL")?.takeIf { it.isNotBlank() }

        // Enable edge-to-edge display (draw behind system bars and cutout)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showAppVersion()
        appUpdater = AppUpdater(this)
        reviewPrompter = ReviewPrompter(this) { !isStreamingUi }

        // Apply fullscreen mode immediately
        enableFullscreenMode()

        // Enable performance mode for gaming (after binding is initialized)
        enablePerformanceMode()

        setupSurface()
        setupUI()
        setupDraggableOverlay()
        setupSettingsButton()
        restoreOverlayPosition()
        restoreSettingsButtonPosition()
        startChecklistUpdates()
        setupModeToggle()
        setupWirelessController()
        setupUpdateCard()
        AccessoryLink.addListener(accessoryListener)
        handleAccessoryIntent(intent)
    }

    // ==================== USB accessory (no ADB, issue #3) ====================

    /** Opened by an accessory intent: connect as soon as the Mac answers. */
    private var autoConnectAccessory = false

    private val accessoryListener: (AccessoryLink.State) -> Unit = { state ->
        log("USB accessory: $state")
        if (state == AccessoryLink.State.READY && autoConnectAccessory && !isConnected) {
            autoConnectAccessory = false
            if (prefs.connectionMode != ConnectionMode.USB) binding.modeToggleGroup.check(R.id.modeUSB)
            updateStatus(getString(R.string.status_connecting))
            connect("127.0.0.1", AccessoryLink.port)
        }
        if (state == AccessoryLink.State.CLOSED && isConnected && connectedOverAccessory) disconnect()
        if (!isConnected) updateChecklist()
    }

    /** The current stream goes through AccessoryLink. */
    private var connectedOverAccessory = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAccessoryIntent(intent)
    }

    private fun handleAccessoryIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val accessory = AccessoryLink.accessoryFrom(intent) ?: return
        // Launched by the Mac plugging in: Android granted the permission with the intent.
        autoConnectAccessory = true
        AccessoryLink.open(this, accessory)
        if (AccessoryLink.state == AccessoryLink.State.READY) accessoryListener(AccessoryLink.State.READY)
    }

    /** The tablet is already in accessory mode (app opened by hand): pick it up. */
    private fun resumeAccessory() {
        if (AccessoryLink.state != AccessoryLink.State.CLOSED) return
        val accessory = AccessoryLink.attachedAccessory(this) ?: return
        val usb = getSystemService(UsbManager::class.java)
        if (usb.hasPermission(accessory)) {
            AccessoryLink.open(this, accessory)
        } else {
            val flags = android.app.PendingIntent.FLAG_IMMUTABLE
            val permissionIntent =
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .setAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                        .putExtra(UsbManager.EXTRA_ACCESSORY, accessory),
                    flags,
                )
            usb.requestPermission(accessory, permissionIntent)
        }
    }

    // ==================== Self-update card ====================

    private fun setupUpdateCard() {
        binding.updateButton.setOnClickListener { UpdateUi.startUpdate(this) }
        binding.updateNotesButton.setOnClickListener {
            SelfUpdater.state.value.manifestOrNull?.let { UpdateUi.openReleaseNotes(this, it) }
        }
        binding.updateDismissButton.setOnClickListener {
            SelfUpdater.state.value.manifestOrNull?.let { SelfUpdater.dismiss(it) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(SelfUpdater.state, SelfUpdater.refreshTick) { state, _ -> state }
                    .collect { renderUpdateCard(it) }
            }
        }
    }

    private fun renderUpdateCard(state: UpdateState) {
        val manifest = state.manifestOrNull
        if (manifest == null || !SelfUpdater.cardVisible(state)) {
            binding.updateCard.visibility = View.GONE
            return
        }
        binding.updateCard.visibility = View.VISIBLE
        binding.updateTitle.text = getString(R.string.update_available_title, manifest.versionName)
        binding.updateBody.text =
            getString(
                R.string.update_available_body,
                SelfUpdater.installedVersionName(),
                android.text.format.Formatter.formatShortFileSize(this, manifest.size),
            )
        val busy = state is UpdateState.Downloading || state is UpdateState.Installing
        binding.updateProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (state is UpdateState.Downloading) {
            binding.updateProgress.setProgressCompat(state.percent.coerceAtLeast(0), true)
        } else if (state is UpdateState.Installing) {
            binding.updateProgress.setProgressCompat(100, true)
        }
        val status = UpdateUi.progressText(this, state)
        binding.updateStatus.text = status
        binding.updateStatus.visibility = if (status != null) View.VISIBLE else View.GONE
        binding.updateButton.isEnabled = !busy
        binding.updateDismissButton.visibility = if (busy) View.INVISIBLE else View.VISIBLE
    }

    /** Streams and the demo hold off update checks and installs; back home, check if due. */
    private fun onStreamingUiChanged() {
        SelfUpdater.streaming = isStreamingUi
        if (!isStreamingUi && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) SelfUpdater.maybeAutoCheck()
    }

    private fun setupModeToggle() {
        // Restore previous mode and reflect in toggle.
        val saved = prefs.connectionMode
        binding.modeToggleGroup.check(if (saved == ConnectionMode.WIRELESS) R.id.modeWireless else R.id.modeUSB)
        applyModeVisibility(saved)

        // A RadioGroup (pill segments): one segment is always checked.
        binding.modeToggleGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == View.NO_ID) return@setOnCheckedChangeListener
            val mode = if (checkedId == R.id.modeWireless) ConnectionMode.WIRELESS else ConnectionMode.USB
            prefs.connectionMode = mode
            applyModeVisibility(mode)
            if (mode == ConnectionMode.WIRELESS) {
                wirelessController.show()
            }
        }
    }

    private fun applyModeVisibility(mode: ConnectionMode) {
        binding.usbModeContent.visibility = if (mode == ConnectionMode.USB) View.VISIBLE else View.GONE
        binding.wirelessModeContent.visibility = if (mode == ConnectionMode.WIRELESS) View.VISIBLE else View.GONE
        // USB checklist polls 127.0.0.1:port every 2s via adb-reverse to verify Mac
        // server reachability. While in Wireless mode that probe creates loopback
        // connections that fight the wireless session for the Mac's single client
        // slot — kicking the wireless client off seconds after it auths. Pause
        // checklist updates whenever Wireless is the active tab.
        if (mode == ConnectionMode.WIRELESS) {
            stopChecklistUpdates()
        } else {
            startChecklistUpdates()
        }
    }

    private fun setupWirelessController() {
        wirelessController =
            WirelessTabController(
                activity = this,
                views =
                    WirelessTabController.Views(
                        connecting = binding.wirelessConnecting,
                        firstTime = binding.wirelessFirstTime,
                        connected = binding.wirelessConnected,
                        pairedIdle = binding.wirelessPairedIdle,
                        repair = binding.wirelessTokenMismatch,
                        permDenied = binding.wirelessPermDenied,
                        scanButton = binding.wirelessScanButton,
                        rescanButton = binding.wirelessRescanButton,
                        enterCodeButton = binding.wirelessEnterCodeButton,
                        repairEnterCodeButton = binding.wirelessRepairEnterCodeButton,
                        permDeniedEnterCodeButton = binding.wirelessPermDeniedEnterCodeButton,
                        disconnectButton = binding.wirelessDisconnectButton,
                        forgetButton = binding.wirelessForgetButton,
                        reconnectButton = binding.wirelessReconnectButton,
                        idleForgetButton = binding.wirelessIdleForgetButton,
                        openSettingsButton = binding.wirelessOpenSettingsButton,
                        connectedMacName = binding.connectedMacName,
                        connectedMacIp = binding.connectedMacIp,
                        connectingLabel = binding.connectingLabel,
                        connectingSubtitle = binding.connectingSubtitle,
                        idleMacName = binding.idleMacName,
                        idleMacIp = binding.idleMacIp,
                        repairTitle = binding.repairTitle,
                        repairMessage = binding.repairMessage,
                    ),
                storage = pairedHostStorage,
                cameraPerm = cameraPerm,
                onConnectRequested = { host, port, token, deviceName, macName ->
                    connectWireless(host, port, token, deviceName, macName)
                },
                onCodePairRequested = { showCodePairingDialog() },
            )
        wirelessController.bind()
        binding.wirelessDisconnectButton.setOnClickListener { disconnect() }
        if (prefs.connectionMode == ConnectionMode.WIRELESS) {
            wirelessController.show()
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WirelessTabController.REQ_SCAN && resultCode == RESULT_OK) {
            val url = data?.getStringExtra(QRScannerActivity.EXTRA_URL) ?: return
            wirelessController.onScanResult(url)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WirelessTabController.REQ_CAMERA) {
            val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            wirelessController.onCameraPermissionResult(granted)
        }
    }

    /**
     * Enable performance mode for streaming
     * NOTE: setSustainedPerformanceMode is DISABLED - it causes thermal throttling
     * which makes the entire device laggy. Normal power management is more efficient.
     */
    private fun enablePerformanceMode() {
        try {
            // REMOVED: setSustainedPerformanceMode(true)
            // Sustained performance mode forces max CPU/GPU clocks which causes
            // thermal throttling on extended use, making the device laggy.
            // Let the SoC manage power efficiently instead.

            // Use PARTIAL_WAKE_LOCK with timeout to prevent battery drain
            // Screen is already kept on via FLAG_KEEP_SCREEN_ON
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SharpScreen::PerformanceMode",
                )
            // 30 minute timeout instead of infinite acquire
            wakeLock?.acquire(30 * 60 * 1000L)

            log("🎮 Performance mode ENABLED (balanced)")
        } catch (e: Exception) {
            log("⚠️ Performance mode failed: ${e.message}")
        }
    }

    /**
     * Enable fullscreen immersive mode
     * Uses modern WindowInsets API on Android R+ for better system compatibility
     * Also handles display cutout (notch) to use full screen area
     */
    private fun enableFullscreenMode() {
        // Ensure we draw behind the cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    /**
     * Disable fullscreen mode (when disconnected)
     */
    private fun disableFullscreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSurface() {
        binding.surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    mainDiag("surfaceCreated")
                    log("Surface created")
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("surfaceChanged: ${width}x$height")
                    log("Surface changed: ${width}x$height")
                    currentSurfaceHolder = holder
                    initializeDecoderForCurrentSurface()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    mainDiag("surfaceDestroyed")
                    log("Surface destroyed")
                    if (!decoderUsingTextureView) {
                        videoDecoder?.release()
                        videoDecoder = null
                    }
                    currentSurfaceHolder = null
                }
            },
        )

        binding.textureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("textureAvailable: ${width}x$height")
                    currentTextureSurface = Surface(surface)
                    initializeDecoderForCurrentSurface()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("textureSizeChanged: ${width}x$height")
                    applyTextureTransform()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    mainDiag("textureDestroyed")
                    if (decoderUsingTextureView) {
                        videoDecoder?.release()
                        videoDecoder = null
                    }
                    currentTextureSurface?.release()
                    currentTextureSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }

        if (binding.textureView.isAvailable && currentTextureSurface == null) {
            binding.textureView.surfaceTexture?.let { currentTextureSurface = Surface(it) }
        }

        binding.surfaceView.setOnTouchListener { view, event ->
            if (demoActive) showDemoTouchHint(event) else handleTouch(view, event)
            true
        }
        binding.textureView.setOnTouchListener { view, event ->
            if (demoActive) showDemoTouchHint(event) else handleTouch(view, event)
            true
        }
    }

    private fun setupUI() {
        binding.connectButton.setOnClickListener {
            // USB host/port are edited in SettingsActivity (defaults 127.0.0.1:54321).
            var host = prefs.usbHost.trim().ifEmpty { PreferencesManager.DEFAULT_USB_HOST }
            val port = prefs.usbPort

            // Convert localhost to 127.0.0.1 for better Android compatibility
            if (host.equals("localhost", ignoreCase = true)) {
                host = "127.0.0.1"
            }

            // Validate input
            if (host.isBlank()) {
                showError(getString(R.string.error_enter_host))
                return@setOnClickListener
            }

            updateStatus(getString(R.string.status_connecting))
            // Over the USB accessory when the Mac switched the tablet into it.
            if (AccessoryLink.state == AccessoryLink.State.READY) {
                connect("127.0.0.1", AccessoryLink.port)
            } else {
                connect(host, port)
            }
        }

        binding.disconnectButton.setOnClickListener {
            disconnect()
        }

        binding.appSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.demoButton.setOnClickListener { startDemo() }
        binding.watchVideoButton.setOnClickListener { showHowItWorksVideo() }
        binding.getMacAppLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(MAC_APP_URL)))
            } catch (_: Exception) {
                showError(getString(R.string.error_open_mac_app_url, MAC_APP_URL))
            }
        }
        binding.reportProblemLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(REPORT_PROBLEM_URL)))
            } catch (_: Exception) {
                showError(getString(R.string.error_open_report_url, REPORT_PROBLEM_URL))
            }
        }

        // Initial status
        updateStatus(getString(R.string.status_ready))
    }

    // ==================== Demo Mode ====================

    private fun startDemo() {
        if (demoActive || isConnected) return
        val player =
            try {
                DemoPlayer(applicationContext) { videoDecoder }
            } catch (e: Exception) {
                mainDiag("Demo load failed: ${e.message}")
                showError(getString(R.string.error_demo_failed, e.message.toString()))
                return
            }
        mainDiag("Demo started")
        demoActive = true
        demoTouchHintShown = false
        // Cancel any in-flight connection attempt (USB connect or wireless
        // auto-reconnect) so its callbacks can't feed real frames into the demo
        // decoder or flip the UI to "connected" mid-demo.
        streamClient?.disconnect()
        streamClient = null
        videoDecoder?.release()
        videoDecoder = null

        displayWidth = DemoPlayer.WIDTH
        displayHeight = DemoPlayer.HEIGHT
        desktopWidth = 0
        desktopHeight = 0
        displayRotation = 0
        displayFlipHorizontal = false
        displayFlipVertical = false

        binding.settingsPanel.visibility = View.GONE
        applySettingsButtonVisibility()
        restoreSettingsButtonPosition()
        enableFullscreenMode()
        applyRotation(0, false, false)
        // The clip is a real 16:10 extended display: letterbox it instead of stretching
        // UI text on phones (a real session sizes the Mac display to the tablet).
        setSurfaceAspect("${DemoPlayer.WIDTH}:${DemoPlayer.HEIGHT}")
        updateResolutionOverlay()
        binding.resolutionText.append(getString(R.string.stat_demo_suffix))
        binding.latencyText.text = "—"
        updateOverlayVisibility(prefs.showStatsOverlay)
        initializeDecoderForCurrentSurface()

        player.onStats = { fps, mbps ->
            runOnUiThread {
                binding.fpsText.text = String.format("%.1f", fps)
                binding.bitrateText.text = String.format("%.1f Mbps", mbps)
            }
        }
        demoPlayer = player
        player.start()
        android.widget.Toast
            .makeText(this, R.string.toast_demo_started, android.widget.Toast.LENGTH_LONG)
            .show()
    }

    private fun stopDemo() {
        if (!demoActive) return
        mainDiag("Demo stopped")
        demoPlayer?.stop()
        demoPlayer = null
        demoActive = false
        videoDecoder?.release()
        videoDecoder = null
        displayWidth = 0
        displayHeight = 0

        binding.settingsButton.visibility = View.GONE
        binding.statusBar.visibility = View.GONE
        binding.settingsPanel.visibility = View.VISIBLE
        setSurfaceAspect(null)
        // Recreate the surface so the last demo frame doesn't linger behind the panel
        binding.surfaceView.visibility = View.GONE
        binding.surfaceView.post { binding.surfaceView.visibility = View.VISIBLE }
        disableFullscreenMode()
        resetOrientationToSensor()
    }

    private fun setSurfaceAspect(ratio: String?) {
        val params = binding.surfaceView.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = ratio
        binding.surfaceView.layoutParams = params
    }

    /** Plays the bundled recording of a real Mac + phone session, looping, full screen. */
    private fun showHowItWorksVideo() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_how_it_works)
        val video = dialog.findViewById<android.widget.VideoView>(R.id.howItWorksVideo)
        video.setVideoURI(android.net.Uri.parse("android.resource://$packageName/${R.raw.how_it_works}"))
        video.setOnPreparedListener { player ->
            player.isLooping = true
            video.start()
        }
        dialog.findViewById<View>(R.id.closeVideoButton).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { video.stopPlayback() }
        dialog.show()
    }

    /** Touch has nothing to control in demo mode — say what it would do instead. */
    private fun showDemoTouchHint(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_UP || demoTouchHintShown) return
        demoTouchHintShown = true
        android.widget.Toast
            .makeText(
                this,
                R.string.toast_demo_touch,
                android.widget.Toast.LENGTH_LONG,
            ).show()
    }

    private fun showError(message: String) {
        runOnUiThread {
            // connect() fails on a background thread, often seconds after the user rotated
            // the tablet or left the app. By then this Activity is destroyed and Dialog.show()
            // throws BadTokenException (top crash on 1.0.2). The status line already says
            // "Connection failed", so just drop the dialog.
            if (isFinishing || isDestroyed) {
                mainDiag("showError skipped — activity gone: $message")
                return@runOnUiThread
            }
            try {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.error_title_connection)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            } catch (e: android.view.WindowManager.BadTokenException) {
                mainDiag("showError failed — no window token: ${e.message}")
            }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupDraggableOverlay() {
        binding.statusBar.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingOverlay = true
                    overlayDx = view.x - event.rawX
                    overlayDy = view.y - event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingOverlay) {
                        // Calculate new position
                        var newX = event.rawX + overlayDx
                        var newY = event.rawY + overlayDy

                        // Constrain to the parent. The overlay can be wider than a
                        // phone in portrait; OverlayBounds pins it instead of throwing.
                        val parent = view.parent as View
                        newX = OverlayBounds.clamp(newX, parent.width, view.width)
                        newY = OverlayBounds.clamp(newY, parent.height, view.height)

                        view
                            .animate()
                            .x(newX)
                            .y(newY)
                            .setDuration(0)
                            .start()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDraggingOverlay) {
                        // Save position
                        prefs.overlayX = view.x
                        prefs.overlayY = view.y
                        isDraggingOverlay = false
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }
    }

    private fun restoreOverlayPosition() {
        val x = prefs.overlayX
        val y = prefs.overlayY

        if (x >= 0 && y >= 0) {
            binding.statusBar.post {
                binding.statusBar.x = x
                binding.statusBar.y = y
            }
        }

        // Apply opacity to both overlay and settings button
        val opacity = prefs.overlayOpacity
        updateOverlayOpacity(opacity)
        updateSettingsButtonOpacity(opacity)

        // Apply visibility
        updateOverlayVisibility(prefs.showStatsOverlay)
    }

    private fun updateOverlayOpacity(opacity: Float) {
        binding.statusBar.alpha = opacity
    }

    private fun updateOverlayVisibility(show: Boolean) {
        if ((streamClient != null || demoActive) && show) {
            binding.statusBar.visibility = View.VISIBLE
            // Restore position when showing
            val x = prefs.overlayX
            val y = prefs.overlayY
            if (x >= 0 && y >= 0) {
                binding.statusBar.post {
                    binding.statusBar.x = x
                    binding.statusBar.y = y
                }
            }
        } else {
            binding.statusBar.visibility = View.GONE
        }
    }

    @SuppressLint("InflateParams", "SetTextI18n")
    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = dialog.findViewById<View>(android.R.id.content)
        val showStatsSwitch = view.findViewById<SwitchMaterial>(R.id.showStatsSwitch)
        val hideSettingsSwitch = view.findViewById<SwitchMaterial>(R.id.hideSettingsSwitch)
        val opacitySlider = view.findViewById<Slider>(R.id.opacitySlider)
        val opacityValue = view.findViewById<TextView>(R.id.opacityValue)
        val resetButton = view.findViewById<View>(R.id.resetPositionButton)
        val resetSettingsBtn = view.findViewById<View>(R.id.resetSettingsButton)
        val disconnectButton = view.findViewById<View>(R.id.disconnectSettingsButton)
        val closeButton = view.findViewById<View>(R.id.closeButton)

        // Only show Disconnect when actually streaming. Otherwise the button is
        // a no-op and confuses users into clicking it twice.
        disconnectButton.visibility = if (isStreamingUi) View.VISIBLE else View.GONE
        if (demoActive) (disconnectButton as TextView).setText(R.string.display_exit_demo)

        // Position buttons (8 directions)
        val cornerTopLeft = view.findViewById<MaterialButton>(R.id.cornerTopLeft)
        val cornerTopRight = view.findViewById<MaterialButton>(R.id.cornerTopRight)
        val cornerBottomLeft = view.findViewById<MaterialButton>(R.id.cornerBottomLeft)
        val cornerBottomRight = view.findViewById<MaterialButton>(R.id.cornerBottomRight)
        val positionTopCenter = view.findViewById<MaterialButton>(R.id.positionTopCenter)
        val positionBottomCenter = view.findViewById<MaterialButton>(R.id.positionBottomCenter)
        val positionCenterLeft = view.findViewById<MaterialButton>(R.id.positionCenterLeft)
        val positionCenterRight = view.findViewById<MaterialButton>(R.id.positionCenterRight)

        // Load current settings
        showStatsSwitch.isChecked = prefs.showStatsOverlay
        hideSettingsSwitch.isChecked = prefs.hideSettingsButton
        opacitySlider.value = prefs.overlayOpacity
        opacityValue.text = "${(prefs.overlayOpacity * 100).toInt()}%"

        // Highlight current position selection (8 positions)
        // 0=BottomRight, 1=BottomLeft, 2=TopRight, 3=TopLeft
        // 4=TopCenter, 5=BottomCenter, 6=CenterLeft, 7=CenterRight
        fun updatePositionSelection(selectedPosition: Int) {
            val buttons =
                listOf(
                    cornerBottomRight,
                    cornerBottomLeft,
                    cornerTopRight,
                    cornerTopLeft,
                    positionTopCenter,
                    positionBottomCenter,
                    positionCenterLeft,
                    positionCenterRight,
                )
            buttons.forEachIndexed { index, button ->
                if (index == selectedPosition) {
                    button.backgroundTintList =
                        android.content.res.ColorStateList
                            .valueOf(getColor(R.color.ss_accent_tint))
                } else {
                    button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                }
            }
        }
        updatePositionSelection(prefs.settingsButtonCorner)

        // Setup listeners
        showStatsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.showStatsOverlay = isChecked
            updateOverlayVisibility(isChecked)
        }

        hideSettingsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.hideSettingsButton = isChecked
            if (isStreamingUi) {
                applySettingsButtonVisibility()
            }
            if (isChecked) {
                android.widget.Toast
                    .makeText(
                        this,
                        R.string.toast_settings_icon_hidden,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
            }
        }

        opacitySlider.addOnChangeListener { _, value, _ ->
            prefs.overlayOpacity = value
            updateOverlayOpacity(value)
            updateSettingsButtonOpacity(value)
            opacityValue.text = "${(value * 100).toInt()}%"
        }

        resetButton.setOnClickListener {
            prefs.overlayX = -1f
            prefs.overlayY = -1f
            // Use displayMetrics for reliable positioning
            val dm = resources.displayMetrics
            binding.statusBar
                .animate()
                .x(dm.widthPixels - binding.statusBar.width - 48f)
                .y(48f)
                .setDuration(300)
                .start()
        }

        // Position button listeners (8 directions)
        cornerBottomRight.setOnClickListener {
            prefs.settingsButtonCorner = 0
            updatePositionSelection(0)
            updateSettingsButtonPosition(0)
        }

        cornerBottomLeft.setOnClickListener {
            prefs.settingsButtonCorner = 1
            updatePositionSelection(1)
            updateSettingsButtonPosition(1)
        }

        cornerTopRight.setOnClickListener {
            prefs.settingsButtonCorner = 2
            updatePositionSelection(2)
            updateSettingsButtonPosition(2)
        }

        cornerTopLeft.setOnClickListener {
            prefs.settingsButtonCorner = 3
            updatePositionSelection(3)
            updateSettingsButtonPosition(3)
        }

        positionTopCenter.setOnClickListener {
            prefs.settingsButtonCorner = 4
            updatePositionSelection(4)
            updateSettingsButtonPosition(4)
        }

        positionBottomCenter.setOnClickListener {
            prefs.settingsButtonCorner = 5
            updatePositionSelection(5)
            updateSettingsButtonPosition(5)
        }

        positionCenterLeft.setOnClickListener {
            prefs.settingsButtonCorner = 6
            updatePositionSelection(6)
            updateSettingsButtonPosition(6)
        }

        positionCenterRight.setOnClickListener {
            prefs.settingsButtonCorner = 7
            updatePositionSelection(7)
            updateSettingsButtonPosition(7)
        }

        resetSettingsBtn.setOnClickListener {
            prefs.settingsButtonCorner = 0
            updatePositionSelection(0)
            updateSettingsButtonPosition(0)
        }

        disconnectButton.setOnClickListener {
            dialog.dismiss()
            if (demoActive) stopDemo() else disconnect()
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        // Cap dialog height to 85% of screen so content scrolls on smaller screens / landscape
        dialog.window?.let { win ->
            val maxH = (resources.displayMetrics.heightPixels * 0.85).toInt()
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, maxH)
        }
    }

    private fun updateSettingsButtonOpacity(opacity: Float) {
        binding.settingsButton.alpha = opacity
    }

    private fun setupSettingsButton() {
        // Simple click to show settings dialog
        // Position can be changed via corner buttons in settings
        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        // Escape hatch for the hidden icon: the back gesture briefly reveals it
        // instead of leaving the app. Back is not forwarded to the Mac, so this
        // cannot conflict with streamed touch input.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isStreamingUi && prefs.hideSettingsButton &&
                        binding.settingsButton.visibility != View.VISIBLE
                    ) {
                        revealSettingsButtonTemporarily()
                    } else if (demoActive) {
                        stopDemo()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }

    /** Streaming-time visibility of the settings icon, honoring the hide preference. */
    private fun applySettingsButtonVisibility() {
        binding.settingsButton.visibility =
            if (prefs.hideSettingsButton) View.GONE else View.VISIBLE
    }

    private val revealHandler = Handler(Looper.getMainLooper())
    private val hideSettingsButtonRunnable =
        Runnable {
            if (isStreamingUi && prefs.hideSettingsButton) {
                binding.settingsButton.visibility = View.GONE
            }
        }

    private fun revealSettingsButtonTemporarily() {
        binding.settingsButton.visibility = View.VISIBLE
        revealHandler.removeCallbacks(hideSettingsButtonRunnable)
        revealHandler.postDelayed(hideSettingsButtonRunnable, 5_000L)
    }

    private fun restoreSettingsButtonPosition() {
        updateSettingsButtonPosition(prefs.settingsButtonCorner)
    }

    /**
     * Use ConstraintSet to position settings button - most reliable method
     * Works correctly with orientation changes
     * Supports 8 positions: 4 corners + 4 edges
     */
    private fun updateSettingsButtonPosition(position: Int) {
        val constraintLayout = binding.root as ConstraintLayout
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)

        val buttonId = binding.settingsButton.id
        val marginDp = (24 * resources.displayMetrics.density).toInt()

        // Clear all constraints first
        constraintSet.clear(buttonId, ConstraintSet.TOP)
        constraintSet.clear(buttonId, ConstraintSet.BOTTOM)
        constraintSet.clear(buttonId, ConstraintSet.START)
        constraintSet.clear(buttonId, ConstraintSet.END)

        when (position) {
            0 -> { // Bottom Right (default)
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    marginDp,
                )
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginDp)
            }

            1 -> { // Bottom Left
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    marginDp,
                )
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    marginDp,
                )
            }

            2 -> { // Top Right
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, marginDp)
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginDp)
            }

            3 -> { // Top Left
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, marginDp)
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    marginDp,
                )
            }

            4 -> { // Top Center
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, marginDp)
                constraintSet.connect(buttonId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            }

            5 -> { // Bottom Center
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    marginDp,
                )
                constraintSet.connect(buttonId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            }

            6 -> { // Center Left
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
                constraintSet.connect(buttonId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    marginDp,
                )
            }

            7 -> { // Center Right
                constraintSet.connect(buttonId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
                constraintSet.connect(buttonId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginDp)
            }

            else -> { // Default to bottom right
                constraintSet.connect(
                    buttonId,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    marginDp,
                )
                constraintSet.connect(buttonId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginDp)
            }
        }

        // Reset any absolute positioning that might have been set
        binding.settingsButton.translationX = 0f
        binding.settingsButton.translationY = 0f

        constraintSet.applyTo(constraintLayout)
    }

    /**
     * Display config from a new Mac always arrives AFTER codecSelected, so a
     * missing negotiation at this point proves the Mac app predates H.264
     * support — surface that instead of a silent black screen.
     */
    private fun warnIfAvcOnlyWithoutNegotiation() {
        if (!CodecCapabilities.hasHevcDecoder && streamClient?.codecNegotiated != true) {
            mainDiag("AVC-only device but Mac did not negotiate codec — Mac app too old")
            runOnUiThread {
                updateStatus(getString(R.string.status_no_hevc))
            }
        }
    }

    /**
     * Recreate the decoder when the negotiated stream codec doesn't match the
     * decoder's mime. Display config and codecSelected can arrive in either
     * order on reconnect; without this, a decoder created with the default
     * HEVC mime keeps consuming the H.264 stream and never outputs a frame —
     * a permanent black screen on AVC-only devices (e.g. Unisoc tablets).
     */
    private fun onStreamCodecSelected(isHevc: Boolean) {
        val expectedMime =
            if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        runOnUiThread {
            val dec = videoDecoder
            when {
                dec == null -> {
                    mainDiag("Codec selected ($expectedMime) — initializing deferred decoder")
                    initializeDecoderForCurrentSurface()
                }
                dec.mime != expectedMime -> {
                    mainDiag("Stream codec is $expectedMime but decoder is ${dec.mime} — recreating")
                    dec.release()
                    videoDecoder = null
                    initializeDecoderForCurrentSurface()
                }
            }
        }
    }

    /**
     * Stream size first, since that is what the decoder and every other stat in this overlay
     * describe. The desktop size is appended only when it differs, so a scaled HiDPI desktop is
     * visible rather than silently standing in for the resolution actually being sent.
     */
    private fun updateResolutionOverlay() {
        if (displayWidth <= 0 || displayHeight <= 0) return
        val stream = "${displayWidth}x$displayHeight"
        val differs = desktopWidth > 0 && (desktopWidth != displayWidth || desktopHeight != displayHeight)
        binding.resolutionText.text =
            if (differs) "$stream (desktop ${desktopWidth}x$desktopHeight)" else stream
    }

    private fun shouldUseTextureView(): Boolean = displayFlipHorizontal || displayFlipVertical

    private fun activeVideoSurface(): Pair<Surface, Boolean>? {
        return if (shouldUseTextureView()) {
            currentTextureSurface?.takeIf { it.isValid }?.let { it to true }
        } else {
            currentSurfaceHolder?.surface?.takeIf { it.isValid }?.let { it to false }
        }
    }

    private fun initializeDecoderForCurrentSurface() {
        if (displayWidth <= 0 || displayHeight <= 0) {
            mainDiag("initializeDecoder skipped — no display config yet")
            return
        }
        // AVC-only device: an HEVC decoder can never decode the H.264 stream
        // the Mac will send — defer until codecSelected arrives, then
        // onStreamCodecSelected initializes with the correct mime.
        if (!demoActive && !CodecCapabilities.hasHevcDecoder && streamClient?.codecNegotiated != true) {
            mainDiag("initializeDecoder deferred — AVC-only device awaiting codec negotiation")
            return
        }

        val (surface, useTextureView) =
            activeVideoSurface() ?: run {
                val kind = if (shouldUseTextureView()) "TextureView" else "SurfaceView"
                mainDiag("initializeDecoder skipped — no valid $kind surface")
                return
            }

        if (videoDecoder != null && decoderUsingTextureView == useTextureView) {
            try {
                videoDecoder?.updateResolution(displayWidth, displayHeight)
                return
            } catch (e: Exception) {
                // Restarting the codec in place failed (crash on 1.0.1): build a fresh
                // decoder below instead, which reports failure in the UI rather than crashing.
                mainDiag("Decoder resize FAILED: ${e.message} — recreating")
            }
        }

        videoDecoder?.release()
        videoDecoder = null
        decoderUsingTextureView = useTextureView

        mainDiag(
            "initializeDecoder called, surface=$surface, valid=${surface.isValid}, " +
                "res=${displayWidth}x$displayHeight, texture=$useTextureView",
        )
        try {
            val displayObj =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
            val mime =
                if (demoActive || streamClient?.streamCodecIsHevc == false) {
                    MediaFormat.MIMETYPE_VIDEO_AVC
                } else {
                    MediaFormat.MIMETYPE_VIDEO_HEVC
                }
            videoDecoder = VideoDecoder(surface, displayObj, displayWidth, displayHeight, mime)
            videoDecoder?.onFrameDecoded = { buffer ->
                streamClient?.releaseBuffer(buffer)
            }
            videoDecoder?.onKeyframeRequired = { force, reason ->
                streamClient?.requestKeyframe(force = force, reason = reason)
            }
            videoDecoder?.onDecoderStalled = {
                // Black screen with live stats: tell the user why instead of
                // staying silent (issue #41). Toast renders above the (black)
                // SurfaceView; the settings panel is hidden while streaming.
                // The sustainable size, not the decoder's advertised one — that is typically far
                // above anything it can really output, which made this message actively misleading.
                val panel = PanelGeometry.of(displayObj)
                val cap =
                    panel?.let {
                        CodecCapabilities.maxStreamSize(
                            mime,
                            it.width,
                            it.height,
                            CodecCapabilities.REFERENCE_FPS,
                        )
                    }
                runOnUiThread {
                    val capText = cap?.let { getString(R.string.toast_decoder_stalled_cap, it.first, it.second) } ?: ""
                    android.widget.Toast
                        .makeText(
                            this,
                            getString(R.string.toast_decoder_stalled, capText),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                }
            }
            streamClient?.requestKeyframe(force = true, reason = "decoder initialized")
            if (videoDecoder?.compositor != null) streamClient?.requestLosslessTileResync()
            mainDiag("Decoder initialized OK ${displayWidth}x$displayHeight mime=$mime, texture=$useTextureView")
            log("✅ Decoder initialized ${displayWidth}x$displayHeight $mime (${displayObj?.refreshRate ?: 60f}Hz)")
        } catch (e: Exception) {
            decoderUsingTextureView = false
            mainDiag("Decoder init FAILED: ${e.message}")
            log("❌ Failed to initialize decoder: ${e.message}")
            runOnUiThread {
                updateStatus(getString(R.string.status_decoder_failed, e.message.toString()))
                // The status text sits in the settings panel, hidden while streaming —
                // without this the user would just see a black screen.
                if (isStreamingUi) {
                    android.widget.Toast
                        .makeText(
                            this,
                            R.string.toast_decoder_failed,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }

    /**
     * Wire up all StreamClient callbacks. Used by both USB connect() and wireless connectWireless().
     */
    private fun setupStreamClientCallbacks() {
        streamClient?.onTileInvalidate = { tiles -> videoDecoder?.compositor?.invalidateTiles(tiles) }
        streamClient?.onLosslessTiles = { tileSize, tiles -> videoDecoder?.compositor?.applyTiles(tileSize, tiles) }
        streamClient?.onFrameReceived = { frameData, frameSize, timestamp, isKeyframe ->
            val dec = videoDecoder
            if (dec != null) {
                dec.decode(frameData, frameSize, timestamp, isKeyframe)
            } else {
                mainDiag("FRAME DROPPED: videoDecoder is null!")
            }
        }

        videoDecoder?.onFrameDecoded = { buffer ->
            streamClient?.releaseBuffer(buffer)
        }

        streamClient?.onLatencyMeasured = { rttMs ->
            runOnUiThread {
                binding.latencyText.text = String.format("%.1f ms", rttMs)
            }
        }

        streamClient?.onConnectionStatus = { connected ->
            runOnUiThread {
                isConnected = connected
                reviewPrompter.onConnectionChanged(connected)
                if (connected) {
                    updateStatus(getString(R.string.status_connected))
                } else {
                    updateStatus(getString(R.string.status_disconnected))
                }
                binding.connectButton.isEnabled = !connected
                binding.disconnectButton.isEnabled = connected
                binding.statusIndicator.setBackgroundResource(
                    if (connected) R.drawable.status_indicator_green else R.drawable.status_indicator_red,
                )
                if (connected) {
                    startPingTimer()
                    stopChecklistUpdates()
                    enableFullscreenMode()
                    binding.settingsPanel.visibility = View.GONE
                    applySettingsButtonVisibility()
                    restoreSettingsButtonPosition()
                    updateOverlayVisibility(prefs.showStatsOverlay)
                    // For wireless mode, transition controller to CONNECTED here —
                    // not in MainActivity.connectWireless's coroutine after the
                    // receive loop returns (that runs AFTER disconnect, causing
                    // a stale CONNECTED transition that hides the PAIRED_IDLE UI).
                    if (prefs.connectionMode == ConnectionMode.WIRELESS) {
                        val entry = pairedHostStorage.load()
                        wirelessController.onConnectSuccess(
                            entry?.macName ?: "Mac",
                            entry?.host ?: "—",
                        )
                    }
                } else {
                    stopPingTimer()
                    disableFullscreenMode()
                    resetOrientationToSensor()
                    binding.settingsPanel.visibility = View.VISIBLE
                    binding.settingsButton.visibility = View.GONE
                    binding.statusBar.visibility = View.GONE
                    val mode = prefs.connectionMode
                    val willTransition = mode == ConnectionMode.WIRELESS
                    android.util.Log.i(
                        "MainActivity",
                        "onConnectionStatus(false) — mode=$mode, willTransition=$willTransition",
                    )
                    if (mode == ConnectionMode.WIRELESS) {
                        // Don't restart checklist (it conflicts with wireless on Mac).
                        // Tell wireless controller to show the idle/reconnect UI.
                        wirelessController.onStreamDisconnected()
                    } else {
                        log("📋 Restarting checklist updates")
                        startChecklistUpdates()
                    }
                }
            }
        }

        streamClient?.onDesktopSize = { w, h ->
            desktopWidth = w
            desktopHeight = h
            runOnUiThread { updateResolutionOverlay() }
        }

        streamClient?.onCodecSelected = { isHevc -> onStreamCodecSelected(isHevc) }

        streamClient?.onDisplaySize = { width, height, rotation, flipHorizontal, flipVertical ->
            mainDiag("onDisplaySize: ${width}x$height @ $rotation°, h=$flipHorizontal, v=$flipVertical")
            warnIfAvcOnlyWithoutNegotiation()
            displayWidth = width
            displayHeight = height
            displayRotation = rotation
            displayFlipHorizontal = flipHorizontal
            displayFlipVertical = flipVertical
            runOnUiThread {
                updateResolutionOverlay()
                applyRotation(rotation, flipHorizontal, flipVertical)
                initializeDecoderForCurrentSurface()
            }
            log("Display: ${width}x$height @ $rotation°")
        }

        streamClient?.onStats = { fps, mbps ->
            runOnUiThread {
                binding.fpsText.text = String.format("%.1f", fps)
                binding.bitrateText.text = String.format("%.1f Mbps", mbps)
            }
        }
    }

    /**
     * Code pairing (issue #35): manual host/port/code entry for tablets without
     * a camera. On success the Mac issues the real token and we connect through
     * the same path as a QR scan.
     */
    private fun showCodePairingDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_code_pairing, null)
        val hostField = view.findViewById<android.widget.EditText>(R.id.codePairHost)
        val portField = view.findViewById<android.widget.EditText>(R.id.codePairPort)
        val codeField = view.findViewById<android.widget.EditText>(R.id.codePairCode)
        val errorText = view.findViewById<android.widget.TextView>(R.id.codePairError)

        // Re-pair convenience: prefill the last paired Mac so only the fresh
        // code needs typing.
        pairedHostStorage.load()?.let { cached ->
            hostField.setText(cached.host)
            portField.setText(cached.port.toString())
        }

        val dialog =
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pair_code_title)
                .setView(view)
                .setPositiveButton(R.string.pair_code_pair, null) // listener installed below so it doesn't auto-dismiss
                .setNegativeButton(R.string.cancel, null)
                .create()

        dialog.setOnShowListener {
            val pairButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            pairButton.setOnClickListener {
                val host = hostField.text.toString().trim()
                val port = portField.text.toString().trim().toIntOrNull()
                val code = codeField.text.toString().filter { it.isDigit() }

                errorText.visibility = View.GONE
                when {
                    host.isEmpty() -> {
                        errorText.setText(R.string.pair_error_host)
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    port == null || port !in 1..65535 -> {
                        errorText.setText(R.string.pair_error_port)
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    code.length != AuthHandshake.PAIR_CODE_LEN -> {
                        errorText.text = getString(R.string.pair_error_code_length, AuthHandshake.PAIR_CODE_LEN)
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                }
                pairButton.isEnabled = false
                pairButton.setText(R.string.pair_code_pairing)

                fun showPairError(message: String) {
                    runOnUiThread {
                        pairButton.isEnabled = true
                        pairButton.setText(R.string.pair_code_pair)
                        errorText.text = message
                        errorText.visibility = View.VISIBLE
                    }
                }

                val deviceName = WirelessTabController.defaultDeviceName(this@MainActivity)
                pairingJob =
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val client = StreamClient(host, port!!, applicationContext)
                            val result = client.pairWithCode(code, deviceName)
                            // Dialog dismissed mid-flight (Cancel/back): drop the
                            // token instead of silently connecting.
                            ensureActive()
                            runOnUiThread {
                                dialog.dismiss()
                                wirelessController.onCodePairComplete(host, port, result.token, result.macName)
                            }
                        } catch (e: StreamClient.PairingError) {
                            showPairError(
                                when (e) {
                                    is StreamClient.PairingError.CodeRejected ->
                                        getString(R.string.pair_error_code_rejected)
                                    is StreamClient.PairingError.HostTooOld ->
                                        getString(R.string.pair_error_host_too_old)
                                },
                            )
                        } catch (e: StreamClient.WirelessConnectError) {
                            showPairError(
                                when (e) {
                                    is StreamClient.WirelessConnectError.NetworkUnreachable ->
                                        getString(R.string.pair_error_no_response, host, port)
                                    else -> getString(R.string.pair_error_connection)
                                },
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("Code pairing failed unexpectedly: ${e.message}")
                            showPairError(getString(R.string.pair_error_failed))
                        }
                    }
            }
        }
        dialog.setOnDismissListener {
            pairingJob?.cancel()
            pairingJob = null
        }
        dialog.show()
    }

    private fun connectWireless(
        host: String,
        port: Int,
        token: ByteArray,
        deviceName: String,
        macName: String,
    ) {
        stopDemo() // a real connection (incl. wireless auto-reconnect) takes over the decoder
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("Connecting wirelessly to $host:$port...")
                streamClient = StreamClient(host, port, applicationContext)
                setupStreamClientCallbacks()
                streamClient?.connectWireless(token, deviceName)
                // NOTE: onConnectSuccess is fired from the onConnectionStatus(true)
                // listener (above) right after handshake OK — not here. This line
                // would otherwise run AFTER the receive loop exits, i.e. AFTER
                // disconnect, incorrectly transitioning back to CONNECTED.
            } catch (e: StreamClient.WirelessConnectError) {
                runOnUiThread {
                    wirelessController.onConnectError(e)
                }
            } catch (e: Exception) {
                log("Wireless connect failed: ${e.message}")
                runOnUiThread {
                    wirelessController.onConnectError(StreamClient.WirelessConnectError.NetworkUnreachable)
                }
            }
        }
    }

    private fun connect(
        host: String,
        port: Int,
    ) {
        stopDemo()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("Connecting to $host:$port...")

                connectedOverAccessory = AccessoryLink.state == AccessoryLink.State.READY && port == AccessoryLink.port
                streamClient = StreamClient(host, port, applicationContext)
                streamClient?.onTileInvalidate = { tiles -> videoDecoder?.compositor?.invalidateTiles(tiles) }
                streamClient?.onLosslessTiles = { tileSize, tiles -> videoDecoder?.compositor?.applyTiles(tileSize, tiles) }
                streamClient?.onFrameReceived = { frameData, frameSize, timestamp, isKeyframe ->
                    val dec = videoDecoder
                    if (dec != null) {
                        dec.decode(frameData, frameSize, timestamp, isKeyframe)
                    } else {
                        streamClient?.releaseBuffer(frameData)
                    }
                }

                // Wire up buffer release callback for buffer pooling
                // When decode completes, buffer is returned to StreamClient's pool
                videoDecoder?.onFrameDecoded = { buffer ->
                    streamClient?.releaseBuffer(buffer)
                }
                videoDecoder?.onKeyframeRequired = { force, reason ->
                    streamClient?.requestKeyframe(force = force, reason = reason)
                }

                // Latency measurement via ping/pong
                streamClient?.onLatencyMeasured = { rttMs ->
                    runOnUiThread {
                        binding.latencyText.text = String.format("%.1f ms", rttMs)
                    }
                }

                streamClient?.onConnectionStatus = { connected ->
                    runOnUiThread {
                        // Update connection state flag
                        isConnected = connected
                        reviewPrompter.onConnectionChanged(connected)

                        if (connected) {
                            updateStatus(getString(R.string.status_connected))
                        } else {
                            updateStatus(getString(R.string.status_disconnected))
                        }

                        binding.connectButton.isEnabled = !connected
                        binding.disconnectButton.isEnabled = connected

                        // Update status indicator color
                        binding.statusIndicator.setBackgroundResource(
                            if (connected) {
                                R.drawable.status_indicator_green
                            } else {
                                R.drawable.status_indicator_red
                            },
                        )

                        if (connected) {
                            // Start periodic ping for latency measurement
                            startPingTimer()

                            // Stop checklist updates when connected (prevents socket conflicts)
                            stopChecklistUpdates()

                            // Enter fullscreen mode when connected
                            enableFullscreenMode()

                            binding.settingsPanel.visibility = View.GONE
                            applySettingsButtonVisibility()
                            restoreSettingsButtonPosition()
                            updateOverlayVisibility(prefs.showStatsOverlay)
                        } else {
                            // Stop ping timer
                            stopPingTimer()

                            // Exit fullscreen mode when disconnected
                            disableFullscreenMode()

                            // Reset to follow device sensor when disconnected
                            resetOrientationToSensor()

                            binding.settingsPanel.visibility = View.VISIBLE
                            binding.settingsButton.visibility = View.GONE
                            binding.statusBar.visibility = View.GONE

                            // Restart checklist updates immediately
                            log("📋 Restarting checklist updates")
                            startChecklistUpdates()
                        }
                    }
                }

                streamClient?.onCodecSelected = { isHevc -> onStreamCodecSelected(isHevc) }

                streamClient?.onDesktopSize = { w, h ->
                    desktopWidth = w
                    desktopHeight = h
                    runOnUiThread { updateResolutionOverlay() }
                }

                streamClient?.onDisplaySize = { width, height, rotation, flipHorizontal, flipVertical ->
                    mainDiag("onDisplaySize: ${width}x$height @ $rotation°, h=$flipHorizontal, v=$flipVertical")
                    warnIfAvcOnlyWithoutNegotiation()
                    displayWidth = width
                    displayHeight = height
                    displayRotation = rotation
                    displayFlipHorizontal = flipHorizontal
                    displayFlipVertical = flipVertical

                    runOnUiThread {
                        updateResolutionOverlay()
                        applyRotation(rotation, flipHorizontal, flipVertical)
                        initializeDecoderForCurrentSurface()
                    }
                    log("Display: ${width}x$height @ $rotation°")
                }

                streamClient?.onStats = { fps, mbps ->
                    runOnUiThread {
                        binding.fpsText.text = String.format("%.1f", fps)
                        binding.bitrateText.text = String.format("%.1f Mbps", mbps)
                    }
                }

                streamClient?.connect()
            } catch (e: Exception) {
                val errorMessage =
                    when {
                        e.message?.contains("ECONNREFUSED") == true -> {
                            getString(R.string.error_server_not_running)
                        }

                        e.message?.contains("Network is unreachable") == true -> {
                            getString(R.string.error_unreachable)
                        }

                        e.message?.contains("timeout") == true -> {
                            getString(R.string.error_timeout, port)
                        }

                        else -> {
                            getString(R.string.error_connection_failed, e.message.toString(), port)
                        }
                    }
                updateStatus(getString(R.string.status_connection_failed))
                showError(errorMessage)
            }
        }
    }

    private fun disconnect() {
        stopPingTimer()
        streamClient?.disconnect()
        // Reset display config so next connect defers decoder init until config arrives
        displayWidth = 0
        displayHeight = 0
        desktopWidth = 0
        desktopHeight = 0
        displayFlipHorizontal = false
        displayFlipVertical = false
        runOnUiThread {
            binding.textureView.visibility = View.GONE
            applyTextureTransform()
        }
        log("Disconnected")
    }

    private fun startPingTimer() {
        stopPingTimer()
        pingJob =
            lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(1000) // Ping every 1 second
                    streamClient?.sendPing()
                }
            }
    }

    private fun stopPingTimer() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun cleanup() {
        try {
            stopDemo()
            disconnect()
            videoDecoder?.release()
            videoDecoder = null
            currentTextureSurface?.release()
            currentTextureSurface = null

            // Release wake lock safely
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (e: Exception) {
                // Ignore wake lock release errors
            }
            wakeLock = null
            log("🎮 Performance mode DISABLED")
        } catch (e: Exception) {
            log("⚠️ Cleanup error: ${e.message}")
        }
    }

    private fun handleTouch(
        view: View,
        event: MotionEvent,
    ) {
        val rawX = event.x / view.width.toFloat()
        val rawY = event.y / view.height.toFloat()
        val x = if (displayFlipHorizontal) 1f - rawX else rawX
        val y = if (displayFlipVertical) 1f - rawY else rawY
        val pointerCount = event.pointerCount.coerceAtMost(2)

        var x2 = 0f
        var y2 = 0f
        if (pointerCount >= 2) {
            val rawX2 = event.getX(1) / view.width.toFloat()
            val rawY2 = event.getY(1) / view.height.toFloat()
            x2 = if (displayFlipHorizontal) 1f - rawX2 else rawX2
            y2 = if (displayFlipVertical) 1f - rawY2 else rawY2
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inputPredictor.reset()
                inputPredictor.addSample(x, y)
                streamClient?.sendTouch(x, y, 0, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                streamClient?.sendTouch(x, y, 0, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1) {
                    inputPredictor.addSample(x, y)
                    val (px, py) = inputPredictor.predictPosition(12f)
                    streamClient?.sendTouch(px, py, 1, 1)
                } else {
                    streamClient?.sendTouch(x, y, 1, pointerCount, x2, y2)
                }
            }

            MotionEvent.ACTION_UP -> {
                inputPredictor.reset()
                streamClient?.sendTouch(x, y, 2, 1)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                streamClient?.sendTouch(x, y, 2, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_CANCEL -> {
                inputPredictor.reset()
                streamClient?.sendTouch(x, y, 2, 1)
            }
        }
    }

    private fun applyRotation(
        rotation: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
    ) {
        requestedOrientation =
            when (rotation) {
                90 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }

        binding.surfaceView.rotation = 0f
        binding.surfaceView.visibility = View.VISIBLE
        binding.textureView.visibility = if (flipHorizontal || flipVertical) View.VISIBLE else View.GONE
        applyTextureTransform()

        log(
            "🔄 Orientation: ${when (rotation) {
                90 -> "Portrait"
                180 -> "Landscape (flipped)"
                270 -> "Portrait (flipped)"
                else -> "Landscape"
            }}${if (flipHorizontal || flipVertical) " mirrored" else ""}",
        )
    }

    private fun applyTextureTransform() {
        val view = binding.textureView
        val matrix = Matrix()
        val centerX = view.width / 2f
        val centerY = view.height / 2f
        matrix.postScale(
            if (displayFlipHorizontal) -1f else 1f,
            if (displayFlipVertical) -1f else 1f,
            centerX,
            centerY,
        )
        view.setTransform(matrix)
    }

    /**
     * Reset orientation to follow device sensor (when disconnected)
     */
    private fun resetOrientationToSensor() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun log(message: String) {
        runOnUiThread {
            val current = binding.logText.text.toString()
            val lines = current.split("\n").takeLast(5)
            binding.logText.text = (lines + message).joinToString("\n")
        }
    }

    /**
     * While streaming, ask for the panel's top refresh mode (see [DisplayRefresh]);
     * otherwise hand the choice back to the system. UI thread only.
     */
    private fun applyRefreshRatePreference() {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return
        val wanted =
            if (isStreamingUi) {
                with(DisplayRefresh) {
                    highestRefreshMode(display.supportedModes.map { it.toMode() }, display.mode.toMode())?.id
                        ?: display.mode.modeId
                }
            } else {
                0 // no preference
            }
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != wanted) {
            attrs.preferredDisplayModeId = wanted
            window.attributes = attrs
            Log.i("DisplayRefresh", "preferredDisplayModeId=$wanted (streaming=$isStreamingUi)")
        }
    }

    override fun onResume() {
        super.onResume()
        // Only nudge about updates while idle — never over a live stream or the demo.
        if (!isStreamingUi) appUpdater.checkForUpdate()
        SelfUpdater.onActivityResumed(this)
        if (!isStreamingUi) SelfUpdater.maybeAutoCheck()
        resumeAccessory()
        SelfUpdater.takeUpdatedTo()?.let {
            android.widget.Toast.makeText(this, getString(R.string.update_done, it), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        SelfUpdater.onActivityPaused(this)
    }

    override fun onDestroy() {
        AccessoryLink.removeListener(accessoryListener)
        super.onDestroy()
        stopChecklistUpdates()
        cleanup()
    }

    /** "SharpScreen" with the installed version beside it, smaller and grey. */
    private fun showAppVersion() {
        val version =
            try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                null
            } ?: return
        val name = getString(R.string.brand_name)
        val text = android.text.SpannableString("$name  v$version")
        val start = name.length
        text.setSpan(android.text.style.RelativeSizeSpan(0.5f), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.ss_text_secondary)), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.appTitle.text = text
    }

    // ==================== Connection Checklist ====================

    private fun startChecklistUpdates() {
        // Stop any existing runnable first to prevent duplicates
        checklistRunnable?.let {
            checklistHandler.removeCallbacks(it)
        }

        checklistRunnable =
            object : Runnable {
                override fun run() {
                    updateChecklist()
                    checklistHandler.postDelayed(this, 2000) // Update every 2 seconds
                }
            }
        checklistHandler.post(checklistRunnable!!)
    }

    private fun stopChecklistUpdates() {
        checklistRunnable?.let {
            checklistHandler.removeCallbacks(it)
            checklistRunnable = null
        }
    }

    private fun updateChecklist() {
        // Skip if connected (to prevent socket conflicts)
        if (isConnected) return

        // USB accessory mode: no developer options, ADB or probe needed.
        val accessory = AccessoryLink.state != AccessoryLink.State.CLOSED
        binding.rowDeveloperMode.visibility = if (accessory) View.GONE else View.VISIBLE
        binding.rowUsbDebugging.visibility = if (accessory) View.GONE else View.VISIBLE
        binding.textMacServer.setText(if (accessory) R.string.checklist_mac_accessory else R.string.checklist_mac_server)
        if (accessory) {
            val ready = AccessoryLink.state == AccessoryLink.State.READY
            updateChecklistItem(binding.checkUsbConnected, binding.hintUsbConnected, true)
            updateChecklistItem(binding.checkMacServer, binding.hintMacServer, ready)
            updateMainStatus(ready)
            return
        }

        // Check Developer Mode (if we can run this app with USB debugging, dev mode is enabled)
        val isDeveloperModeEnabled =
            Settings.Secure.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            ) == 1
        updateChecklistItem(binding.checkDeveloperMode, binding.hintDeveloperMode, isDeveloperModeEnabled)

        // Check USB Debugging (ADB enabled)
        val isAdbEnabled =
            Settings.Secure.getInt(
                contentResolver,
                Settings.Global.ADB_ENABLED,
                0,
            ) == 1
        updateChecklistItem(binding.checkUsbDebugging, binding.hintUsbDebugging, isAdbEnabled)

        // A USB data link to a computer (not just a charger)
        val isUsbConnected = isConnectedToComputer()
        updateChecklistItem(binding.checkUsbConnected, binding.hintUsbConnected, isUsbConnected)

        // Check Mac Server (try to connect to port)
        val port = prefs.usbPort
        lifecycleScope.launch(Dispatchers.IO) {
            // Double-check connection state before socket test
            if (isConnected) return@launch

            val isServerRunning = checkServerRunning("127.0.0.1", port)
            runOnUiThread {
                // Final check before updating UI
                if (isConnected) return@runOnUiThread

                updateChecklistItem(binding.checkMacServer, binding.hintMacServer, isServerRunning)

                // Update main status indicator based on all checklist items
                val allReady = isDeveloperModeEnabled && isAdbEnabled && isUsbConnected && isServerRunning
                updateMainStatus(allReady)
            }
        }
    }

    private fun updateMainStatus(allReady: Boolean) {
        binding.statusIndicator.setBackgroundResource(
            if (allReady) {
                R.drawable.status_indicator_green
            } else {
                R.drawable.status_indicator_red
            },
        )
        binding.statusText.setText(if (allReady) R.string.status_ready else R.string.status_not_ready)
    }

    /** Green check when OK; otherwise an orange alert plus a one-line hint on what to do. */
    private fun updateChecklistItem(
        indicator: android.widget.ImageView,
        hint: View,
        isOk: Boolean,
    ) {
        indicator.setImageResource(if (isOk) R.drawable.ic_check_circle else R.drawable.ic_alert_circle)
        hint.visibility = if (isOk) View.GONE else View.VISIBLE
    }

    /**
     * Connected to a computer over USB (a data link, issue: a charger alone
     * turned this green). Android's sticky USB_STATE broadcast reports
     * `connected` only when a USB host has enumerated the tablet; a wall
     * charger or power bank never does. Charging state is not used: with the
     * cable in, Samsung's battery protection or a weak Mac port can report
     * "not charging" while USB works (the 0.3.x bug this replaced).
     */
    private fun isConnectedToComputer(): Boolean {
        // Sticky broadcast UsbManager.ACTION_USB_STATE (hidden constant).
        val usbState = registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        return usbState?.getBooleanExtra("connected", false) == true
    }

    /**
     * Check if Mac server is actually running (not just ADB reverse)
     *
     * Problem: When `adb reverse tcp:8888 tcp:8888` is active, ADB daemon listens on port 8888.
     * A simple socket connect will succeed to ADB daemon, not the actual Mac server.
     *
     * Solution: After connecting, try to read data with a short timeout.
     * Mac server sends display config (type=1) immediately upon connection.
     * ADB daemon doesn't send anything, so read will timeout → false.
     */
    private fun checkServerRunning(
        host: String,
        port: Int,
    ): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 300) // 300ms connect timeout
            socket.soTimeout = 200 // 200ms read timeout

            // Try to read - Mac server sends display config immediately
            // ADB daemon doesn't send anything, so read will timeout
            val input = socket.getInputStream()
            val firstByte = input.read() // Blocks up to soTimeout

            // If we got data (>= 0), it's the real Mac server
            // -1 means EOF (connection closed), anything else is data
            firstByte >= 0
        } catch (e: Exception) {
            // Timeout, connection refused, or other error = server not running
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
