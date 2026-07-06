package com.darkerst.cameraflow

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install before anything else can throw.
        CrashHandler.install(this)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        val lastCrash = CrashHandler.getLastCrash(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (lastCrash != null) {
                        CrashScreen(
                            trace = lastCrash,
                            onDismiss = {
                                CrashHandler.clearLastCrash(this@MainActivity)
                                recreate()
                            }
                        )
                    } else {
                        CameraScreen()
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable
private fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    LaunchedEffect(trace) {
        val clipboard = context.getSystemService<ClipboardManager>()
        clipboard?.setPrimaryClip(ClipData.newPlainText("Crash trace", trace))
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "De app is gecrasht. De foutmelding is automatisch gekopieerd \u2014 plak hem waar je wilt, of selecteer handmatig hieronder:",
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = trace,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = {
                val clipboard = context.getSystemService<ClipboardManager>()
                clipboard?.setPrimaryClip(ClipData.newPlainText("Crash trace", trace))
                Toast.makeText(context, "Foutmelding gekopieerd", Toast.LENGTH_SHORT).show()
            }) {
                Text("Kopiëren")
            }
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("OK, opnieuw proberen")
        }
    }
}
