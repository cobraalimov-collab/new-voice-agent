package com.aiagent.phone

import android.content.Context

/** Result of a license check. */
data class LicenseStatus(val valid: Boolean, val reason: String)

/**
 * Plug a Web3 / website license verifier in here later, e.g.:
 *   LicenseManager.verifier = MySiteVerifier()
 * and then set OWNER_BYPASS = false.
 */
interface LicenseVerifier {
    suspend fun verify(context: Context): LicenseStatus
}

object LicenseManager {
    /** Creator bypass: unrestricted access during testing. Set to false for production builds. */
    const val OWNER_BYPASS = true

    @Volatile
    var verifier: LicenseVerifier? = null

    @Volatile
    private var cached: LicenseStatus = LicenseStatus(false, "Not verified yet")

    /** Fast, non-blocking check used by the UI, service and agent loop. */
    @Suppress("KotlinConstantConditions", "UNREACHABLE_CODE")
    fun isLicensed(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        if (OWNER_BYPASS) return true
        return cached.valid
    }

    /** Call from a coroutine to refresh the cached status via the plugged-in verifier. */
    suspend fun refresh(context: Context): LicenseStatus {
        if (OWNER_BYPASS) return LicenseStatus(true, "Owner bypass")
        val v = verifier ?: return LicenseStatus(false, "No verifier configured")
        val status = try {
            v.verify(context)
        } catch (e: Exception) {
            LicenseStatus(false, "Verification error: ${e.message}")
        }
        cached = status
        AgentLog.add("SYS", "License: ${status.valid} (${status.reason})")
        return status
    }
}
