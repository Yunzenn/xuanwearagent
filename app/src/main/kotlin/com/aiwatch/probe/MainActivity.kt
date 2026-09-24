package com.aiwatch.probe

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private lateinit var output: TextView
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 12f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            listOf(
                "Device" to R.string.tab_device,
                "Audio" to R.string.tab_audio,
                "Graphics" to R.string.tab_graphics,
                "Storage" to R.string.tab_storage,
            ).forEach { (section, label) ->
                addView(Button(context).apply {
                    setText(label)
                    setOnClickListener { showSection(section) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs)
            addView(Button(context).apply {
                setText(R.string.debug_session)
                setOnClickListener { startActivity(android.content.Intent(this@MainActivity, DebugSessionActivity::class.java)) }
            })
            addView(Button(context).apply {
                setText(R.string.identity)
                setOnClickListener {
                    activityScope.launch {
                        try {
                            val identity = (application as ProbeApplication).identityStore.getOrCreate()
                            output.text = getString(R.string.identity_value, identity.deviceId, identity.clientId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            output.setText(R.string.identity_unavailable)
                        }
                    }
                }
            })
            addView(Button(context).apply { setText(R.string.export_reports); setOnClickListener { exportReports() } })
            addView(Button(context).apply {
                setText(R.string.reset_identity)
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.reset_identity)
                        .setMessage(R.string.reset_identity_warning)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.reset_identity) { _, _ ->
                            activityScope.launch {
                                try {
                                    val identity = (application as ProbeApplication).identityStore.resetForRebinding(true)
                                    output.text = getString(R.string.identity_value, identity.deviceId, identity.clientId)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { output.setText(R.string.reset_identity_failed) }
                            }
                        }.show()
                }
            })
            addView(ScrollView(context).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        showSection("Device")
    }

    private fun showSection(name: String) {
        output.text = ProbeReport.collect(this).sections[name].orEmpty().entries
            .joinToString("\n") { "${it.key}: ${it.value}" }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun exportReports() {
        val report = ProbeReport.collect(this)
        val directory = File(filesDir, "reports").apply { mkdirs() }
        File(directory, "DeviceCapabilityReport.md").writeText(report.asMarkdown())
        File(directory, "DeviceCapabilityReport.json").writeText(report.asJson())
        output.text = getString(R.string.export_success, directory.absolutePath)
    }
}
