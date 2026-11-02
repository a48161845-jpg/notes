package com.floatnotes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = "FloatNotes"
            textSize = 28f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Плавающий пузырь поверх любых приложений: вставляй текст и картинки, " +
                "ищи похожее среди сохранённого и копируй одним тапом."
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })

        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, pad) }
        root.addView(status)

        root.addView(button("1. Разрешить показ поверх других окон") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("2. Запустить пузырь") { startBubble() })
        root.addView(button("Остановить пузырь") {
            stopService(Intent(this, OverlayService::class.java))
            updateStatus()
        })

        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала дай разрешение (кнопка 1)", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        updateStatus()
        moveTaskToBack(true)
    }

    private fun updateStatus() {
        val perm = if (Settings.canDrawOverlays(this)) "есть ✅" else "нет ❌"
        val run = if (OverlayService.running) "запущен ✅" else "не запущен"
        status.text = "Разрешение «поверх окон»: $perm\nПузырь: $run"
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        status.visibility = View.VISIBLE
    }
}
