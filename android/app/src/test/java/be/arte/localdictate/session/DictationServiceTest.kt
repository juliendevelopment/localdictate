package be.arte.localdictate.session

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import be.arte.localdictate.DictateApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictationServiceTest {
    @Test fun nullRestartAndStaleNotificationNeverArmOrRecord() {
        val handle = Robolectric.buildService(DictationService::class.java).create()
        val service = handle.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            Intent(service, DictationService::class.java).setAction(DictationService.DISARM), 0, 2))
        assertNull(shadowOf(service).lastForegroundNotification)
        val controller = (service.application as DictateApplication).controller
        assertEquals(Phase.OFF, controller.state.value.phase)
        assertNull(controller.service)
        handle.destroy()
    }
    @Test fun unreservedArmIsRejectedBeforeForegroundOrNativeLoad() {
        val handle = Robolectric.buildService(DictationService::class.java).create()
        val service = handle.get()
        service.onStartCommand(Intent(service, DictationService::class.java).setAction(DictationService.ARM), 0, 1)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertEquals(Phase.OFF, (service.application as DictateApplication).controller.state.value.phase)
        handle.destroy()
    }
    @Test fun manifestKeepsMicrophoneServicePrivateAndRequiresAccessibilityBindingPermission() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<DictateApplication>()
        val microphone = app.packageManager.getServiceInfo(ComponentName(app, DictationService::class.java), 0)
        assertFalse(microphone.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, microphone.foregroundServiceType)
        val insertion = app.packageManager.getServiceInfo(ComponentName(app,
            be.arte.localdictate.insertion.Api33InsertionService::class.java), 0)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", insertion.permission)
        assertTrue(insertion.enabled)
        val legacy = app.packageManager.getServiceInfo(ComponentName(app,
            be.arte.localdictate.insertion.InsertionService::class.java), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(legacy.enabled)
        val direct = Robolectric.buildService(be.arte.localdictate.insertion.Api33InsertionService::class.java).create()
        assertTrue(direct.get().onCreateInputMethod() is be.arte.localdictate.insertion.GuardedInputMethod)
        direct.destroy()
        app.resources.getXml(be.arte.localdictate.R.xml.insertion_service).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            val flags = xml.getAttributeIntValue("http://schemas.android.com/apk/res/android", "accessibilityFlags", 0)
            assertTrue(flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR != 0)
            assertTrue(flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        }
        val application = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
        assertEquals(0, application.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
