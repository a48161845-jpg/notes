package com.floatnotes

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** Прозрачная активити: открывает системный выбор картинки и передаёт результат сервису. */
class PickImageActivity : Activity() {

    private val requestCode = 77

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(i, requestCode)
        } catch (e: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = data?.data
        if (requestCode != this.requestCode || resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        Thread {
            val name = Store(applicationContext).importImage(uri)
            runOnUiThread {
                if (name != null) {
                    startService(
                        Intent(this, OverlayService::class.java)
                            .setAction(OverlayService.ACTION_IMAGE)
                            .putExtra(OverlayService.EXTRA_IMAGE, name)
                    )
                }
                finish()
            }
        }.start()
    }
}
