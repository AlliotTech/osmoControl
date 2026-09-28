package com.alliot.osmo.demo.app

import android.app.Application
import com.alliot.osmo.demo.app.media.DownloadCoordinator

class OsmoDemoApplication : Application() {
    /** Process-scoped download queue, shared by the media ViewModel and the foreground DownloadService. */
    val downloadCoordinator: DownloadCoordinator by lazy { DownloadCoordinator(this) }
}
