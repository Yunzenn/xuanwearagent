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

class MainActivity : Activity() {
    private lateinit var output: TextView

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
            addView(Button(context).apply { setText(R.string.export_reports); setOnClickListener { exportReports() } })
            addView(ScrollView(context).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        showSection("Device")
    }

    private fun showSection(name: String) {
        output.text = ProbeReport.collect(this).sections[name].orEmpty().entries
            .joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun exportReports() {
        val report = ProbeReport.collect(this)
        val directory = File(filesDir, "reports").apply { mkdirs() }
        File(directory, "DeviceCapabilityReport.md").writeText(report.asMarkdown())
        File(directory, "DeviceCapabilityReport.json").writeText(report.asJson())
        output.text = getString(R.string.export_success, directory.absolutePath)
    }
}
