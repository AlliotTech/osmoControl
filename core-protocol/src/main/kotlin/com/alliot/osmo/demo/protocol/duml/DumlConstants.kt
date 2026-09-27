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
    const val GIMBAL: Int = 0x04
    const val BATTERY: Int = 0x06
    const val WIFI: Int = 0x07
    const val STREAMING: Int = 0x08
    const val WAKE: Int = 0x53
}

object DumlGeneralCmd {
    const val SESSION_WAKE_KEEPALIVE: Int = 0x2b
}

object DumlWakeCmd {
    const val WAKE_CAMERA: Int = 0x10
}

object DumlWifiCmd {
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
