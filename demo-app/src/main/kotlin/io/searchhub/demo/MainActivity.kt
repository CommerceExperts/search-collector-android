package io.searchhub.demo

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.searchhub.collector.SearchCollector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppNavHost()
            }
        }
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent) {
        val token = SearchCollector.extractDebugToken(intent) ?: return
        if (token.isNotEmpty()) {
            SearchCollector.activateDebugSession(token)
            Toast.makeText(this, "Debug session active: ${token.take(8)}…", Toast.LENGTH_LONG).show()
        }
    }
}
