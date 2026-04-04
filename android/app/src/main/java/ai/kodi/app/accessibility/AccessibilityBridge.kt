package ai.kodi.app.accessibility

import java.util.concurrent.atomic.AtomicReference

object AccessibilityBridge {
    private val svc = AtomicReference<KodiAccessibilityService?>(null)

    fun attach(service: KodiAccessibilityService) {
        svc.set(service)
    }

    fun detach(service: KodiAccessibilityService) {
        svc.compareAndSet(service, null)
    }

    fun get(): KodiAccessibilityService? = svc.get()
}
