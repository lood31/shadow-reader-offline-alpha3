package com.shadowreader.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModelProvider
import com.shadowreader.app.ui.ShadowReaderApp

class MainActivity : ComponentActivity() {
    private lateinit var model: ReaderViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
        )
        model = ViewModelProvider(this)[ReaderViewModel::class.java]
        if (savedInstanceState == null) receive(intent)
        setContent { ShadowReaderApp(model) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }
    private fun receive(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let(model::receiveShare)
        }
    }
    override fun onStop() { model.stop(); super.onStop() }
    override fun onStart() { super.onStart(); if (::model.isInitialized) model.refreshDay() }
}
