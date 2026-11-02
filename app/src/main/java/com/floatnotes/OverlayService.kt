package com.floatnotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlin.math.abs
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.floatnotes.STOP"
        const val ACTION_IMAGE = "com.floatnotes.IMAGE"
        const val EXTRA_IMAGE = "image"
        private const val CHANNEL = "floatnotes"

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: View? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams

    private var panel: View? = null
    private var input: EditText? = null
    private var results: LinearLayout? = null
    private var attachRow: LinearLayout? = null
    private var attachThumb: ImageView? = null

    private var draft = ""
    private var pendingImage: String? = null

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- lifecycle ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = Store(this)
        startAsForeground()
        running = true
        try {
            createBubble()
        } catch (e: Exception) {
            Toast.makeText(this, "Нет разрешения «поверх других окон»", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_IMAGE -> {
                pendingImage = intent.getStringExtra(EXTRA_IMAGE)
                showPanel()
                refreshAttach()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        hidePanelView()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "FloatNotes", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("FloatNotes работает")
            .setContentText("Пузырь на экране. Нажми «Стоп», чтобы закрыть.")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Стоп", stop
                ).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- bubble ----------

    private fun createBubble() {
        val size = dp(56)
        val tv = TextView(this).apply {
            text = "✎"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF5B7CFA.toInt())
            }
        }
        bubbleLp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(220)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleLp.x; startY = bubbleLp.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragged || abs(dx) > slop || abs(dy) > slop) {
                        dragged = true
                        bubbleLp.x = startX + dx.toInt()
                        bubbleLp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(tv, bubbleLp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragged) showPanel()
            }
            true
        }
        bubble = tv
        wm.addView(tv, bubbleLp)
    }

    // ---------- panel ----------

    private inner class PanelRoot(ctx: Context) : LinearLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) hidePanel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(0xFF3A3D4A.toInt(), 18)
        setOnClickListener { onClick() }
    }

    private fun showPanel() {
        if (panel != null) return
        val dm = resources.displayMetrics
        val w = min(dp(440), (dm.widthPixels * 0.94f).toInt())
        val h = min(dp(560), (dm.heightPixels * 0.62f).toInt())

        val root = PanelRoot(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF1E1F24.toInt(), 20)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "FloatNotes"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(pill("—") { hidePanel() })
        header.addView(
            pill("⏻") { stopSelf() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) }
        )
        root.addView(header)

        // input
        val et = EditText(this).apply {
            hint = "Текст или запрос…"
            setHintTextColor(0xFF8A8D99.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(0xFF2A2C33.toInt(), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(draft)
            setSelection(text.length)
        }
        et.addTextChangedListener(object : TextWatcher {
            private val task = Runnable { refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(task)
                handler.postDelayed(task, 150)
            }
        })
        root.addView(et, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        input = et

        // attached image preview
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val aRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        aRow.addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)))
        aRow.addView(TextView(this).apply {
            text = "Картинка прикреплена"
            setTextColor(0xFFB8BBC8.toInt())
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        aRow.addView(pill("✕") {
            pendingImage = null
            refreshAttach()
        })
        root.addView(aRow)
        attachRow = aRow
        attachThumb = thumb

        // buttons
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        fun add(v: View) = buttons.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        })
        add(pill("💾 Сохранить") { save() }.apply { background = rounded(0xFF5B7CFA.toInt(), 18) })
        add(pill("🖼 Файл") {
            startActivity(
                Intent(this@OverlayService, PickImageActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        add(pill("📋 Вставить") { pasteFromClipboard() })
        root.addView(buttons)

        // results
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        results = list

        root.addView(TextView(this).apply {
            text = "Тап — копировать · долгий тап — удалить"
            setTextColor(0xFF7C7F8C.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })

        val lp = WindowManager.LayoutParams(
            w, h, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть окно", Toast.LENGTH_SHORT).show()
            return
        }
        panel = root
        bubble?.visibility = View.GONE
        refreshAttach()
        refresh()
    }

    private fun hidePanelView() {
        draft = input?.text?.toString() ?: draft
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        input = null
        results = null
        attachRow = null
        attachThumb = null
    }

    private fun hidePanel() {
        hidePanelView()
        bubble?.visibility = View.VISIBLE
    }

    private fun refreshAttach() {
        val row = attachRow ?: return
        val name = pendingImage
        if (name == null) {
            row.visibility = View.GONE
        } else {
            attachThumb?.setImageBitmap(store.thumb(name, dp(40)))
            row.visibility = View.VISIBLE
        }
    }

    // ---------- actions ----------

    private fun save() {
        val et = input ?: return
        val text = et.text.toString().trim()
        if (text.isEmpty() && pendingImage == null) {
            toast("Нечего сохранять")
            return
        }
        store.add(text, pendingImage)
        pendingImage = null
        draft = ""
        et.setText("")
        refreshAttach()
        refresh()
        toast("Сохранено")
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("Буфер пуст")
            return
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        val isImage = clip.description.hasMimeType("image/*") ||
            (uri != null && runCatching { contentResolver.getType(uri)?.startsWith("image/") == true }
                .getOrDefault(false))
        if (isImage && uri != null) {
            val name = store.importImage(uri)
            if (name != null) {
                pendingImage = name
                refreshAttach()
            } else toast("Не удалось вставить картинку")
        } else {
            val txt = item.coerceToText(this)?.toString().orEmpty()
            if (txt.isEmpty()) {
                toast("В буфере нет текста")
            } else {
                input?.let { it.setText(it.text.toString() + txt); it.setSelection(it.text.length) }
            }
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("note", text))
        toast("Скопировано")
    }

    private fun copyImage(name: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", store.imageFile(name))
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(contentResolver, "image", uri))
        toast("Картинка скопирована")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- list ----------

    private fun refresh() {
        val box = results ?: return
        val query = input?.text?.toString()?.trim().orEmpty()
        val all = store.all().sortedByDescending { it.ts }
        val shown = if (query.isEmpty()) all.take(30) else Fuzzy.rank(query, all, { it.text }, 50)

        box.removeAllViews()
        if (shown.isEmpty()) {
            box.addView(TextView(this).apply {
                text = if (query.isEmpty()) "Пока пусто. Напиши что-нибудь и нажми «Сохранить»."
                else "Похожего не найдено"
                setTextColor(0xFF7C7F8C.toInt())
                textSize = 13f
                setPadding(dp(4), dp(12), dp(4), 0)
            })
            return
        }
        shown.forEach { box.addView(row(it)) }
    }

    private fun row(n: Note): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFF2A2C33.toInt(), 12)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val img = n.image
        if (img != null) {
            r.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(store.thumb(img, dp(48)))
                setOnClickListener { copyImage(img) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
        }
        r.addView(TextView(this).apply {
            text = if (n.text.isBlank()) "[картинка]" else n.text
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        r.setOnClickListener {
            if (n.text.isNotBlank()) copyText(n.text) else img?.let { copyImage(it) }
        }
        r.setOnLongClickListener {
            store.delete(n.id)
            toast("Удалено")
            refresh()
            true
        }
        return r
    }
}
