package com.nerovision.production

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

object NeroServiceRegistry {
    val accessibility = AtomicReference<NeroAccessibilityService?>()
    val overlay = AtomicReference<NeroOverlayService?>()
    val bridge = AtomicReference<NeroBridgeService?>()
    val projection = AtomicReference<NeroMediaProjectionService?>()
    val audio = AtomicReference<NeroAudioCaptureService?>()
    val watchdog = AtomicReference<NeroWatchdogService?>()

    fun healthJson(): JSONObject {
        return JSONObject()
            .put("accessibilityConnected", accessibility.get() != null)
            .put("overlayConnected", overlay.get() != null)
            .put("bridgeConnected", bridge.get() != null)
            .put("projectionConnected", projection.get() != null)
            .put("audioConnected", audio.get() != null)
            .put("watchdogConnected", watchdog.get() != null)
    }
}
