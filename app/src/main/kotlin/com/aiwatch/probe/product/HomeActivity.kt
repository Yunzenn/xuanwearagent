package com.aiwatch.probe.product

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.aiwatch.live2d.Live2DAvatarView
import com.aiwatch.probe.*
import com.aiwatch.protocol.*
import kotlinx.coroutines.*

/** Product presentation over the existing foreground session. Never starts recording on connect. */
class HomeActivity : Activity() {

    /** Which avatar is actually on screen. Read by the Phase 2B-1A acceptance test. */
    enum class AvatarMode { STATIC, LIVE2D }

    companion object {
        /**
         * Points the Live2D loader at a model that cannot exist so the real failure path runs
         * (initialise -> false -> static fallback) instead of a stubbed one.
         */
        const val EXTRA_FORCE_LIVE2D_FAILURE = "com.aiwatch.probe.FORCE_LIVE2D_FAILURE"

        /**
         * P2B-1A-O1a: debug-only switch that selects the HARNESS_LIKE host configuration (platform
         * defaults + opaque clear) instead of the PRODUCT one. Same APK, same runtime, same model —
         * only the host configuration differs.
         */
        const val EXTRA_HOST_MODE_HARNESS = "com.aiwatch.probe.HOST_MODE_HARNESS"

        /**
         * P2B-1A-O1b: debug-only switch that drives the frame with the official Sample's model semantics
         * instead of our runtime, on the same View/Surface/EGL context and the same Haru assets.
         */
        const val EXTRA_RUNTIME_BACKEND_OFFICIAL = "com.aiwatch.probe.RUNTIME_BACKEND_OFFICIAL"

        /**
         * Debug-only: run the Live2D-independent rasterisation probe in the same GLSurfaceView / GL thread /
         * framebuffer 0 as the product draw, so a black frame can be attributed to the host rather than to
         * the Cubism path.
         */
        const val EXTRA_PLAIN_SHADER_PROBE = "com.aiwatch.probe.PLAIN_SHADER_PROBE"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store get() = (application as ProbeApplication).productStore
    private var session: DebugAudioSession? = null
    private var ticker: Job? = null
    private lateinit var name: TextView
    private lateinit var avatarContainer: FrameLayout
    private lateinit var staticAvatar: ImageView
    private var live2dView: Live2DAvatarView? = null
    private var avatarMode = AvatarMode.STATIC
    private var live2dFailure: String? = null
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var connect: Button
    private lateinit var talk: Button
    private lateinit var interrupt: Button
    private var pressed = false
    private var closing: Job? = null
    private var connecting: Job? = null
    private var foreground = false
    private var touchClick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = ProductUi(this)
        val wide = resources.configuration.screenWidthDp >= 700 &&
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val root = ui.page(if (wide) 960 else 560)
        ui.add(root, ui.text(getString(R.string.product_eyebrow), 12f, ui.muted))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        name = ui.title("")
        header.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.button(getString(R.string.product_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        ui.add(root, header)
        val art = if (wide) ui.column() else root
        val controls = if (wide) ui.column() else root
        if (wide) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(art, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(32) })
            row.addView(controls, LinearLayout.LayoutParams(0, -2, 1f))
            ui.add(root, row, 24)
        }
        status = ui.text(getString(R.string.product_offline), 13f, ui.accent)
        ui.add(controls, status)
        staticAvatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.product_avatar_description)
            setImageResource(R.drawable.avatar_companion)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }
        // The static avatar stays behind the Live2D surface and is only hidden once the model reports
        // ready, so Home can never show an empty frame while the runtime starts up.
        avatarContainer = FrameLayout(this).apply {
            background = ui.shape(ui.surface, 24)
            addView(staticAvatar, FrameLayout.LayoutParams(-1, -1))
        }
        art.addView(avatarContainer, LinearLayout.LayoutParams(-1, ui.dp(if (resources.configuration.screenHeightDp < 500) 180 else 248)))
        ui.add(art, ui.text(getString(R.string.product_local_hint), 12f, ui.muted).apply { gravity = Gravity.CENTER })
        subtitle = ui.text(getString(R.string.product_subtitle_empty), 17f).apply { minHeight = ui.dp(68); setTextIsSelectable(true) }
        ui.add(controls, subtitle)
        talk = ui.button(getString(R.string.product_talk), true) {
            // Accessibility click toggles capture; ordinary touch is hold/release.
            if (!touchClick) { if (pressed) finishTalking() else beginTalking() }
        }.apply {
            contentDescription = getString(R.string.product_accessible_talk)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { view.parent.requestDisallowInterceptTouchEvent(true); beginTalking(); true }
                    MotionEvent.ACTION_UP -> {
                        finishTalking(); view.parent.requestDisallowInterceptTouchEvent(false)
                        touchClick = true
                        try { view.performClick() } finally { touchClick = false }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { pressed = false; session?.stopTalking(); view.parent.requestDisallowInterceptTouchEvent(false); true }
                    else -> true
                }
            }
        }
        ui.add(controls, talk)
        interrupt = ui.button(getString(R.string.product_interrupt)) { pressed = false; session?.interrupt() }
        ui.add(controls, interrupt)
        connect = ui.button(getString(R.string.product_connect)) {
            if (session != null) disconnect() else connectSession()
        }
        ui.add(controls, connect)
        ui.add(root, ui.text(getString(R.string.product_preview_note), 12f, ui.muted))
        render()
        startLive2D()
    }

    /** Current avatar source, for the Phase 2B-1A acceptance test. */
    val currentAvatarMode: AvatarMode get() = avatarMode
    val currentLive2DFailure: String? get() = live2dFailure
    val live2DSurfaceGenerations: Int get() = live2dView?.surfaceGenerations ?: 0
    val live2DFramesDrawn: Long get() = live2dView?.framesDrawn ?: 0L
    val live2DPlainShaderProbeResult: String? get() = live2dView?.plainShaderProbeResult

    /**
     * Starts the Live2D runtime over the static avatar. The runtime is owned by the view that hosts it and
     * is released on detach; nothing here is a process-level singleton holding an Activity.
     */
    private fun startLive2D() {
        val hostMode = if (intent.getBooleanExtra(EXTRA_HOST_MODE_HARNESS, false)) {
            Live2DAvatarView.HostMode.HARNESS_LIKE
        } else {
            Live2DAvatarView.HostMode.PRODUCT
        }
        val backend = if (intent.getBooleanExtra(EXTRA_RUNTIME_BACKEND_OFFICIAL, false)) {
            Live2DAvatarView.RuntimeBackend.OFFICIAL
        } else {
            Live2DAvatarView.RuntimeBackend.OURS
        }
        val view = Live2DAvatarView(
            this,
            null,
            hostMode,
            backend,
            intent.getBooleanExtra(EXTRA_PLAIN_SHADER_PROBE, false),
        )
        if (intent.getBooleanExtra(EXTRA_FORCE_LIVE2D_FAILURE, false)) {
            view.modelDirectory = "does-not-exist/"
            view.modelJson = "missing.model3.json"
        }
        view.listener = object : Live2DAvatarView.Listener {
            override fun onLive2DReady(surfaceGeneration: Int) = runOnUiThread { showLive2D() }
            override fun onLive2DFailed(reason: String) = runOnUiThread { fallbackToStaticAvatar(reason) }
        }
        avatarContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
        live2dView = view
    }

    private fun showLive2D() {
        avatarMode = AvatarMode.LIVE2D
        live2dFailure = null
        staticAvatar.visibility = View.GONE
    }

    /**
     * Any Live2D failure falls back to the Phase 2A static avatar. Home must never go blank or crash
     * because of the Cubism runtime.
     */
    private fun fallbackToStaticAvatar(reason: String) {
        avatarMode = AvatarMode.STATIC
        live2dFailure = reason
        staticAvatar.visibility = View.VISIBLE
        live2dView?.let { view ->
            avatarContainer.removeView(view)
            view.releaseRuntime()
        }
        live2dView = null
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        ticker = scope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { store.character() }
                val bitmap = withContext(Dispatchers.IO) { store.avatar() }
                name.text = profile.name
                if (bitmap == null) staticAvatar.setImageResource(R.drawable.avatar_companion) else staticAvatar.setImageBitmap(bitmap)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice(R.string.product_store_error) }
            while (isActive) { render(); delay(150) }
        }
    }
    private fun connectSession() {
        if (closing?.isActive == true || connecting?.isActive == true) return
        connect.isEnabled = false
        connecting = scope.launch {
            try {
                val endpoint = withContext(Dispatchers.IO) { store.server().endpoint }
                if (!foreground) return@launch
                if (endpoint.isEmpty()) notice(R.string.product_need_server)
                else if (!ProductStore.validEndpoint(endpoint)) notice(R.string.product_endpoint_error)
                else session = DebugAudioSession(application as ProbeApplication, endpoint).also { it.connect() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice(R.string.product_store_error) }
            finally { render() }
        }
    }
    private fun beginTalking() {
        val active = session ?: return
        if (active.state.value.phase != SessionPhase.READY || active.state.value.conversation != ConversationState.IDLE) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            notice(R.string.product_permission); return
        }
        pressed = true; active.startTalking(); render()
    }
    private fun finishTalking() { pressed = false; session?.finishTalking(); render() }
    private fun render() {
        val active = session
        val snapshot = active?.state?.value
        val ready = snapshot?.phase == SessionPhase.READY
        if (!ready) pressed = false
        talk.isEnabled = ready && (snapshot?.conversation == ConversationState.IDLE || snapshot?.conversation == ConversationState.LISTENING)
        talk.setText(if (pressed) R.string.product_talk_release else R.string.product_talk)
        interrupt.isEnabled = ready && snapshot?.conversation in listOf(ConversationState.THINKING, ConversationState.SPEAKING)
        connect.isEnabled = closing?.isActive != true && connecting?.isActive != true
        connect.setText(if (active == null) R.string.product_connect else R.string.product_disconnect)
        val label = when {
            active?.audioError != null -> R.string.product_status_error
            snapshot == null || snapshot.phase == SessionPhase.STOPPED -> R.string.product_offline
            snapshot.phase == SessionPhase.ERROR -> R.string.product_status_error
            snapshot.phase == SessionPhase.AUTH_REQUIRED || snapshot.phase == SessionPhase.ACTIVATING -> R.string.product_status_auth
            snapshot.phase == SessionPhase.RETRY_WAIT -> R.string.product_status_retry
            !ready -> R.string.product_status_connecting
            snapshot.conversation == ConversationState.LISTENING -> R.string.product_status_listening
            snapshot.conversation == ConversationState.THINKING -> R.string.product_status_thinking
            snapshot.conversation == ConversationState.SPEAKING -> R.string.product_status_speaking
            else -> R.string.product_status_ready
        }
        status.setText(label)
        subtitle.text = when {
            snapshot?.activationCode != null -> getString(R.string.product_activation, snapshot.activationCode)
            active?.caption?.value?.text?.isNotBlank() == true -> active.caption.value.let {
                if (it.fromUser) getString(R.string.product_you, it.text) else it.text
            }
            else -> getString(R.string.product_subtitle_empty)
        }
    }
    private fun disconnect() { pressed = false; session?.let { closing = it.close() }; session = null; render() }
    private fun notice(message: Int) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onStop() { foreground = false; connecting?.cancel(); ticker?.cancel(); disconnect(); super.onStop() }
    override fun onPause() { live2dView?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); live2dView?.onResume() }
    override fun onDestroy() {
        // Explicit ownership: the runtime dies with this Activity, never as a process-wide singleton.
        live2dView?.releaseRuntime()
        live2dView = null
        scope.cancel()
        super.onDestroy()
    }
}
