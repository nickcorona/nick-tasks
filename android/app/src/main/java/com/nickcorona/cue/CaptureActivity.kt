package com.nickcorona.cue

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

// Quick capture as a small dialog over whatever's on screen: the widget's +, the launcher
// icon's long-press shortcut, or text shared from another app. Same /capture as the app's box.
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Cue.key(this) == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }
        val dp = { x: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), resources.displayMetrics).toInt() }
        val shared = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
            .filter { it.isNotBlank() }.distinct().joinToString(" ")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(12)) }
        col.addView(TextView(this).apply { text = "Capture"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) })
        val box = EditText(this).apply {
            hint = "Email Sam the ratios…"
            setText(shared)
            setSelection(text.length)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(false)
            maxLines = 4
            setHorizontallyScrolling(false)
        }
        col.addView(box)
        val add = Button(this).apply { text = "Add" }
        col.addView(add, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.END; topMargin = dp(6)
        })
        setContentView(col)
        box.requestFocus()

        val go = {
            val text = box.text.toString().trim()
            if (text.isNotEmpty()) {
                add.isEnabled = false; add.text = "Adding…"
                val app = applicationContext
                thread {
                    val msg = runCatching { Capture.send(app, text) }.getOrElse { "Couldn't save it: ${it.message?.take(60)}" }
                    runOnUiThread { Toast.makeText(app, msg, Toast.LENGTH_LONG).show(); finish() }
                    NowWidget.refresh(app)
                }
            }
        }
        add.setOnClickListener { go() }
        box.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { go(); true } else false }
    }
}
