package org.jcastillo.grabaciones.integrations.mega

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nz.mega.sdk.MegaError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One-off connectivity probe using deliberately invalid, non-account credentials. */
@RunWith(AndroidJUnit4::class)
class MegaApiAuthenticationProbeTest {
    @Test
    fun loginWithoutAppKeyReceivesAuthenticationRejectionFromMegaApi() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("megaApiProbe") != "true") {
            return@runBlocking
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val error = try {
            MegaApiCoordinator.signIn(
                context,
                "pi-probe-nonexistent-2026@example.invalid",
                "deliberately-invalid-password",
            )
            throw AssertionError("The non-existent test account unexpectedly logged in")
        } catch (sdkError: MegaSdkException) {
            sdkError
        }

        assertEquals(MegaOperation.LOGIN, error.operation)
        assertTrue(
            "Expected MEGA's invalid-account/password response, got ${error.errorCode}: ${error.sdkMessage}",
            error.errorCode == MegaError.API_ENOENT || error.errorCode == MegaError.API_EACCESS,
        )
        assertNotNull("The MEGA error string should be propagated", error.sdkMessage)
    }
}
