package com.nerovision.production

object NeroContract {
    const val VERSION = "1.0.0"
    const val LOCALHOST = "127.0.0.1"

    object Ports {
        const val ANDROID_BRIDGE = 8766
        const val VISION_SERVICE = 8767
        const val VOICE_SERVICE = 8768
        const val OPERATOR_SERVICE = 8769
    }

    object Notifications {
        const val CHANNEL_ID = "nerovision.runtime"
        const val BRIDGE_ID = 1001
        const val OVERLAY_ID = 1002
        const val PROJECTION_ID = 1003
        const val AUDIO_ID = 1004
        const val WATCHDOG_ID = 1005
    }

    object Retry {
        const val MAX_ATTEMPTS = 3
        const val BASE_DELAY_MS = 250L
    }

    object Snapshot {
        const val MAX_TREE_DEPTH = 24
        const val MAX_TREE_CHILDREN = 24
        const val VERIFY_WINDOW_MS = 1800L
        const val POLL_INTERVAL_MS = 150L
        const val KEEP_FILES = 64
    }
}
