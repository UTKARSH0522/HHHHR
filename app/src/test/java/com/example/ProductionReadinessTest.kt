package com.example

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.BrandConfig
import com.example.notifications.HRConnectMessagingService
import com.razorpay.PaymentResultWithDataListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProductionReadinessTest {

    @Test
    fun `release build config guard rejects missing backend or placeholder razorpay key`() {
        assertTrue(
            BrandConfig.isReleaseBuildMisconfigured(
                isDebug = false,
                backendBaseUrl = "",
                razorpayKeyId = "rzp_live_123456"
            )
        )
        assertTrue(
            BrandConfig.isReleaseBuildMisconfigured(
                isDebug = false,
                backendBaseUrl = "http://10.0.2.2:8080",
                razorpayKeyId = "rzp_live_123456"
            )
        )
        assertTrue(
            BrandConfig.isReleaseBuildMisconfigured(
                isDebug = false,
                backendBaseUrl = "https://api.hrconnect.in",
                razorpayKeyId = "rzp_test_placeholder"
            )
        )
        assertFalse(
            BrandConfig.isReleaseBuildMisconfigured(
                isDebug = false,
                backendBaseUrl = "https://api.hrconnect.in",
                razorpayKeyId = "rzp_live_prod123"
            )
        )
        assertFalse(
            BrandConfig.isReleaseBuildMisconfigured(
                isDebug = true,
                backendBaseUrl = "",
                razorpayKeyId = "rzp_test_placeholder"
            )
        )
    }

    @Test
    fun `main activity implements razorpay PaymentResultWithDataListener`() {
        assertTrue(PaymentResultWithDataListener::class.java.isAssignableFrom(MainActivity::class.java))
    }

    @Test
    fun `manifest declares FCM messaging service and POST_NOTIFICATIONS permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(perms.contains(android.Manifest.permission.POST_NOTIFICATIONS))

        val serviceInfo = pm.getServiceInfo(
            ComponentName(context, HRConnectMessagingService::class.java),
            PackageManager.GET_META_DATA
        )
        assertFalse(serviceInfo.exported)
    }

    @Test
    fun `FCM notification content sanitizer provides safe fallback titles and trims body`() {
        val bookingFallback = HRConnectMessagingService.buildNotificationContent(
            remoteTitle = null,
            remoteBody = null,
            eventType = "BOOKING_CONFIRMED"
        )
        assertEquals("Consultation Confirmed", bookingFallback.title)
        assertTrue(bookingFallback.body.contains("HRConnect"))

        val custom = HRConnectMessagingService.buildNotificationContent(
            remoteTitle = "  Payout Sent  ",
            remoteBody = "  UTR123456 settled  ",
            eventType = "PAYOUT_COMPLETED"
        )
        assertEquals("Payout Sent", custom.title)
        assertEquals("UTR123456 settled", custom.body)
    }

    @Test
    fun `backend url validator permits local and RFC1918 LAN URLs in debug while keeping release HTTPS-only`() {
        assertTrue(BrandConfig.isValidBackendUrl(isDebug = true, url = "http://10.0.2.2:8080"))
        assertTrue(BrandConfig.isValidBackendUrl(isDebug = true, url = "http://127.0.0.1:8080"))
        assertTrue(BrandConfig.isValidBackendUrl(isDebug = true, url = "http://localhost:8080"))
        assertTrue(BrandConfig.isValidBackendUrl(isDebug = true, url = "http://192.168.1.42:8080"))
        assertTrue(BrandConfig.isValidBackendUrl(isDebug = true, url = "http://10.24.0.15:8080"))
        assertTrue(BrandConfig.isValidBackendUrl(isDebug = true, url = "http://172.20.10.2:8080"))
        assertTrue(BrandConfig.isValidBackendUrl(isDebug = true, url = "https://api.hrconnect.in"))

        // Reject public cleartext HTTP and invalid/out-of-range LAN hosts even in Debug
        assertFalse(BrandConfig.isValidBackendUrl(isDebug = true, url = "http://api.hrconnect.in"))
        assertFalse(BrandConfig.isValidBackendUrl(isDebug = true, url = "http://172.32.0.1:8080"))
        assertFalse(BrandConfig.isValidBackendUrl(isDebug = true, url = "not_configured"))

        // Release strictly allows HTTPS only
        assertTrue(BrandConfig.isValidBackendUrl(isDebug = false, url = "https://api.hrconnect.in"))
        assertFalse(BrandConfig.isValidBackendUrl(isDebug = false, url = "http://192.168.1.42:8080"))
        assertFalse(BrandConfig.isValidBackendUrl(isDebug = false, url = "http://localhost:8080"))
    }

    @Test
    fun `placeholder razorpay key detection identifies dev placeholders cleanly`() {
        assertTrue(BrandConfig.isPlaceholderRazorpayKey(""))
        assertTrue(BrandConfig.isPlaceholderRazorpayKey("rzp_test_placeholder"))
        assertTrue(BrandConfig.isPlaceholderRazorpayKey("rzp_test_replace_me"))
        assertFalse(BrandConfig.isPlaceholderRazorpayKey("rzp_test_1DP5mmOlF5G5ag"))
        assertFalse(BrandConfig.isPlaceholderRazorpayKey("rzp_live_9AbCdEf123456"))
    }
}
