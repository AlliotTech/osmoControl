package com.alliot.osmo.demo.protocol.duml

object DumlConstants {
    const val SOF: Int = 0x55
    const val HEADER_SIZE: Int = 11
    const val CRC16_SIZE: Int = 2
    const val MIN_FRAME_SIZE: Int = HEADER_SIZE + CRC16_SIZE
    const val VERSION: Int = 0x01
}

object DumlFlags {
    const val NOTIFY: Int = 0x00
    const val REQUEST: Int = 0x40
    const val RESPONSE: Int = 0xC0
}

object DumlCmdSet {
    const val GENERAL: Int = 0x00
    const val CAMERA: Int = 0x01
    const val FILE_SYSTEM: Int = 0x02
    const val GIMBAL: Int = 0x04
    const val BATTERY: Int = 0x06
    const val WIFI: Int = 0x07
    const val STREAMING: Int = 0x08
    /** Battery/dock push set (e.g. `0x0d/0x02` power frame). NOT storage. */
    const val POWER: Int = 0x0d
    const val WAKE: Int = 0x53
}

object DumlGeneralCmd {
    const val MEDIA_LIST_QUERY: Int = 0x26
    const val MEDIA_LIST_RESPONSE: Int = 0x27
    const val MEDIA_DELETE: Int = 0x28
    const val SESSION_WAKE_KEEPALIVE: Int = 0x2b
}

object DumlFileSystemCmd {
    const val FAVORITE: Int = 0xBF
    /** Unprompted push: active-store status. flags u32LE @0 (bit 30 = in playback), total MiB u32LE @5, free MiB @9. */
    const val ACTIVE_STORE_STATUS: Int = 0x80
    /** Unprompted push: per-store [total][free] u32LE MiB blocks. Byte 2 = store count; block 1 @6/@10, internal @24/@28 (if body >= 32 B). */
    const val STORES_STATUS: Int = 0xDC
}

/** Unprompted battery/dock push. The byte layout was only ever mapped on the Nano — keep it raw. */
object DumlBatteryCmd {
    const val BATTERY_PUSH: Int = 0x02
}

object DumlWakeCmd {
    const val WAKE_CAMERA: Int = 0x10
}

object DumlWifiCmd {
    const val GET_SSID: Int = 0x07
    const val GET_PASSWORD: Int = 0x0e
    const val SET_PAIRING_PIN: Int = 0x45
    const val PAIRING_APPROVED: Int = 0x46
    const val WIFI_CONNECT: Int = 0x47
}

object DumlGimbalCmd {
    const val RAW_PWM: Int = 0x01
    const val POSITION_TELEMETRY: Int = 0x05
    const val ABSOLUTE_ANGLE: Int = 0x0A
    const val VELOCITY_CONTROL: Int = 0x0C
    const val ABSOLUTE_ANGLE_WITH_DURATION: Int = 0x14
    const val INCREMENTAL_MOVE: Int = 0x15
    const val SET_MODE: Int = 0x4C
}

object DumlTargets {
    const val APP_TO_CAMERA: Int = 0x0102
    const val APP_TO_GIMBAL: Int = 0x0402
    const val APP_TO_WIFI: Int = 0x0702
    const val APP_TO_STREAMING: Int = 0x0802
    const val APP_TO_SESSION: Int = 0xF002
    const val APP_TO_WAKE: Int = 0x1C02
}
