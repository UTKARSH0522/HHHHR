package com.example.core

import com.example.BuildConfig

object BrandConfig {
    const val APP_NAME = "HRConnect"
    const val TAGLINE = "India-First HR Professional Consultation & Income Marketplace"
    const val DEFAULT_TIMEZONE = "Asia/Kolkata"
    const val CURRENCY_CODE = "INR"
    const val DEFAULT_PLATFORM_COMMISSION_BPS = 1500L // 15%
    const val DEFAULT_REFERRAL_COMMISSION_BPS = 1000L // 10%
    const val DEFAULT_GATEWAY_FEE_BPS = 200L // 2.0% Razorpay standard fee estimate
    const val DEFAULT_GST_ON_COMMISSION_BPS = 1800L // 18% GST on platform fee (transparency tracking)
    const val DEFAULT_TDS_BPS = 0L // Configurable 194J / 194O TDS deduction bps
    const val DEFAULT_PAYMENT_HOLD_MINUTES = 10L
    const val DEFAULT_CANCELLATION_WINDOW_HOURS = 24L
    const val DEFAULT_DISPUTE_WINDOW_HOURS = 24L
    const val DEFAULT_MIN_ATTENDANCE_RATIO = 0.65
    const val DEFAULT_MIN_ATTENDANCE_FLOOR_MINUTES = 10L
    const val ADMIN_BOOTSTRAP_EMAIL = "utkarsh.work.contact@gmail.com"

    val razorpayKeyId: String
        get() = BuildConfig.RAZORPAY_KEY_ID.ifBlank { "rzp_test_placeholder" }

    val isRazorpayTestMode: Boolean
        get() = razorpayKeyId.startsWith("rzp_test_") || razorpayKeyId == "rzp_test_placeholder"

    val backendBaseUrl: String
        get() = BuildConfig.HRCONNECT_BACKEND_BASE_URL.trim()
            .takeUnless { it.equals("not_configured", ignoreCase = true) || it.equals("placeholder", ignoreCase = true) }
            ?.trimEnd('/')
            .orEmpty()

    val isBackendConfigured: Boolean
        get() = isValidBackendUrl(isDebug = BuildConfig.DEBUG, url = backendBaseUrl)

    fun isPlaceholderRazorpayKey(keyId: String): Boolean {
        val clean = keyId.trim().lowercase()
        if (clean.isBlank()) return true
        return clean == "rzp_test_placeholder" ||
            clean.contains("placeholder") ||
            clean.contains("replace") ||
            clean.contains("changeme") ||
            clean.contains("your_") ||
            clean.contains("dummy") ||
            clean.contains("example")
    }

    fun isValidBackendUrl(isDebug: Boolean, url: String): Boolean {
        val clean = url.trim()
        if (clean.isBlank() ||
            clean.equals("not_configured", ignoreCase = true) ||
            clean.equals("placeholder", ignoreCase = true)
        ) {
            return false
        }
        if (clean.startsWith("https://", ignoreCase = true)) {
            val host = clean.substringAfter("://").substringBefore('/').substringBefore(':').trim()
            return host.isNotBlank()
        }
        if (!isDebug) return false
        if (!clean.startsWith("http://", ignoreCase = true)) return false
        val authority = clean.substringAfter("://").substringBefore('/')
        val host = authority.substringBefore(':').trim().lowercase()
        if (host.isBlank()) return false
        if (host == "10.0.2.2" || host == "127.0.0.1" || host == "localhost") return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val nums = octets.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val first = nums[0]
        val second = nums[1]
        return first == 10 ||
            (first == 192 && second == 168) ||
            (first == 172 && second in 16..31)
    }

    /**
     * Local sandbox payment / check-in fallback writes are strictly restricted to DEBUG builds.
     * Release builds ALWAYS require an HTTPS production backend and server-side verification.
     */
    val allowDebugSandboxFallback: Boolean
        get() = BuildConfig.DEBUG

    val isReleaseMisconfigured: Boolean
        get() = isReleaseBuildMisconfigured(
            isDebug = BuildConfig.DEBUG,
            backendBaseUrl = backendBaseUrl,
            razorpayKeyId = razorpayKeyId
        )

    fun isReleaseBuildMisconfigured(
        isDebug: Boolean,
        backendBaseUrl: String,
        razorpayKeyId: String
    ): Boolean {
        if (isDebug) return false
        val hasHttpsBackend = isValidBackendUrl(isDebug = false, url = backendBaseUrl)
        val hasRealRazorpayKey = !isPlaceholderRazorpayKey(razorpayKeyId)
        return !hasHttpsBackend || !hasRealRazorpayKey
    }

    val defaultSpecialisations: List<Pair<String, String>> = listOf(
        "Talent Acquisition and Recruitment" to "Hiring strategy, executive search, campus & lateral pipelines",
        "HR Business Partnering" to "Stakeholder alignment, org design, people strategy for business units",
        "Payroll and Compensation" to "CTC structuring, ESOPs, salary benchmarking & statutory payroll",
        "Labour Law and Compliance" to "Indian labour codes, POSH compliance, audits & statutory filings",
        "Employee Relations" to "Workplace investigations, grievances, conflict resolution & retention",
        "Performance Management" to "OKRs, appraisal cycles, PIP frameworks & calibration",
        "Learning and Development" to "Capability building, leadership academies & L&D ROI",
        "HR Operations" to "HRIS setup, onboarding/offboarding workflows & policy governance",
        "HR Strategy and Leadership" to "CHRO advisory, board reporting & culture transformation",
        "Career Guidance for HR Professionals" to "HR career transitions, HRBP growth & specialist-to-leader paths",
        "Resume and LinkedIn Guidance" to "HR resume positioning, LinkedIn authority & ATS optimization",
        "Interview Preparation" to "Mock HR interviews, case rounds & compensation negotiation",
        "Startup HR Setup" to "0-to-1 HR policies, founding team hiring & handbook creation",
        "Organisational Development" to "Change management, M&A integration & org effectiveness",
        "Employee Engagement" to "Pulse surveys, EVP design, hybrid engagement & recognition"
    )

    val supportedDurationsMinutes: List<Long> = listOf(15L, 30L, 45L, 60L, 90L)

    val consultationFormats: List<String> = listOf(
        "Video Consultation",
        "Audio Call",
        "Resume & Async Review"
    )
}
