package com.geniex.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Tiny activity that runs in a separate process and relaunches MainActivity
 * after the inference process is deliberately terminated. This is the only
 * reliable way to guarantee GPU/HTP/QAIRT native allocations are returned
 * before loading a different large model.
 */
class RuntimeRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            finishAndRemoveTask()
        }, 650L)
    }
}
