package com.aiwatch.probe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.widget.*
import kotlinx.coroutines.*
import java.net.URI

/** Engineering panel, not product UI. Endpoints/tokens are not persisted or logged. */
class DebugSessionActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: DebugAudioSession? = null
    private lateinit var status: TextView
    private var poll: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val endpoint = EditText(this).apply {
            setHint(R.string.bootstrap_endpoint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        status = TextView(this).apply { setText(R.string.debug_disconnected); setTextIsSelectable(true) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(endpoint)
            addView(Button(context).apply {
                setText(R.string.debug_connect)
                setOnClickListener {
                    val url = endpoint.text.toString().trim()
                    val uri = runCatching { URI(url) }.getOrNull()
                    if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
                        status.setText(R.string.debug_https_required)
                    } else if (session == null) {
                        session = DebugAudioSession(application as ProbeApplication, url).also { it.connect() }
                    }
                }
            })
            addView(Button(context).apply {
                setText(R.string.debug_talk)
                setOnClickListener { /* accessibility click cannot safely emulate a held microphone */
                    Toast.makeText(context, R.string.debug_hold_instruction, Toast.LENGTH_SHORT).show()
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                            } else session?.startTalking()
                            true
                        }
                        MotionEvent.ACTION_UP -> { session?.finishTalking(); view.performClick(); true }
                        MotionEvent.ACTION_CANCEL -> { session?.stopTalking(); true }
                        else -> true
                    }
                }
            })
            addView(Button(context).apply {
                setText(R.string.debug_interrupt); setOnClickListener { session?.interrupt() }
            })
            addView(Button(context).apply {
                setText(R.string.debug_disconnect); setOnClickListener { disconnect() }
            })
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onStart() {
        super.onStart()
        poll = uiScope.launch {
            while (isActive) {
                session?.let { active ->
                    val state = active.state.value
                    val output = active.output
                    status.text = getString(R.string.debug_status, state.phase.name, state.conversation.name,
                        output?.sampleRate?.toString() ?: "—", output?.channels?.toString() ?: "—",
                        active.txPackets.get(), active.rxPackets.get(), active.queueDepth,
                        state.generation, state.connectionId?.toString() ?: "—",
                        active.readChunks.get(), active.discardedTailSamples.get(),
                        active.audioError ?: state.diagnostic ?: "—", state.activationCode ?: "—",
                        active.paddedFinalFrames.get(), active.paddingSamples.get())
                }
                delay(200)
            }
        }
    }
    private fun disconnect() { session?.close(); session = null; status.setText(R.string.debug_disconnected) }
    override fun onStop() { poll?.cancel(); disconnect(); super.onStop() }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
