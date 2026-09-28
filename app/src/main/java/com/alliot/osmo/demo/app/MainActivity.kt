package com.alliot.osmo.demo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.alliot.osmo.demo.app.di.AppContainer
import com.alliot.osmo.demo.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = AppContainer(applicationContext)
        maybeRequestNotificationPermission()
        setContent {
            AppRoot(container = container)
        }
    }

    /**
     * The background-download service posts an ongoing progress notification; on Android 13+ that needs
     * runtime consent. Best-effort — a denial only costs the notification, downloads still run.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
