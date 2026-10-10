package com.reelrecipes.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.reelrecipes.app.ui.App
import com.reelrecipes.app.ui.ReelTheme

/** What arrived from the Share menu. */
data class Shared(val text: String, val images: List<Uri>, val stamp: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Shared?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = RecipeStore(applicationContext)
        if (savedInstanceState == null) incoming.value = readShare(intent)
        setContent {
            ReelTheme {
                App(store = store, incoming = incoming.value, onIncomingHandled = { incoming.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)?.let { incoming.value = it }
    }

    private fun readShare(intent: Intent?): Shared? {
        if (intent == null) return null
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { !it.contains("instagram.com") },
        ).joinToString("\n")
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val img = if (intent.type?.startsWith("image/") == true) listOfNotNull(streamExtra(intent)) else emptyList()
                Shared(text, img)
            }
            Intent.ACTION_SEND_MULTIPLE -> Shared(text, streamListExtra(intent))
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
}
