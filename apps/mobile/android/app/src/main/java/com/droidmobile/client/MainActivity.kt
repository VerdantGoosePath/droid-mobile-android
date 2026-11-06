package com.droidmobile.client

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.droidmobile.client.backend.BridgeRuntime
import com.droidmobile.client.backend.DroidRepository
import com.droidmobile.client.backend.DroidViewModel
import com.droidmobile.client.backend.SecretStore
import com.droidmobile.client.ui.DroidApp
import com.droidmobile.client.ui.theme.DroidTheme

/**
 * Native entrypoint. The nonvisual 1x1 WebView beneath Compose is strictly an
 * isolated JavaScript SDK runtime; the user never sees web UI content.
 */
class MainActivity : ComponentActivity() {
    private lateinit var runtime: BridgeRuntime
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runtime = BridgeRuntime(this)
        val repository = DroidRepository(runtime, SecretStore(this), applicationContext)
        val vm = DroidViewModel(repository)
        val root = FrameLayout(this)
        root.addView(runtime.webView, FrameLayout.LayoutParams(1, 1).apply {
            leftMargin = 0; topMargin = 0
        })
        val compose = ComposeView(this).apply {
            setContent {
                DroidTheme { DroidApp(vm) }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
    }
    override fun onDestroy() {
        runtime.close()
        super.onDestroy()
    }
}
