package org.jcastillo.grabaciones.integrations.mega

import nz.mega.sdk.MegaError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MegaErrorMessagesTest {
    @Test
    fun loginFailureIncludesSdkDiagnosticsWithoutExposingPasswordOrSessionSecrets() {
        val error = MegaSdkException(
            MegaOperation.LOGIN,
            MegaError.API_EACCESS,
            "denied password=secret-pass session_token=secret-session",
        )

        val message = MegaErrorMessages.login(
            error,
            twoFactorAttempt = false,
            sensitiveValues = listOf("secret-pass", "secret-session"),
        )

        assertTrue(message.contains("Comprueba el correo y la contraseña"))
        assertTrue(message.contains("código ${MegaError.API_EACCESS}"))
        assertTrue(message.contains("mensaje del SDK"))
        assertFalse(message.contains("secret-pass"))
        assertFalse(message.contains("secret-session"))
    }

    @Test
    fun loginAndFetchNodesFailuresAreReportedAsDifferentOperations() {
        val login = MegaErrorMessages.login(
            MegaSdkException(MegaOperation.LOGIN, MegaError.API_EFAILED, "login diagnostic"),
            twoFactorAttempt = false,
        )
        val fetchNodes = MegaErrorMessages.login(
            MegaSdkException(MegaOperation.FETCH_NODES, MegaError.API_EFAILED, "fetch diagnostic"),
            twoFactorAttempt = false,
        )

        assertTrue(login.contains("inicio de sesión"))
        assertTrue(fetchNodes.contains("La sesión se aceptó"))
        assertTrue(fetchNodes.contains("cargar los archivos"))
    }

    @Test
    fun invalidTwoFactorCodeGetsAUsefulSpanishMessage() {
        val message = MegaErrorMessages.login(
            MegaSdkException(MegaOperation.LOGIN, MegaError.API_EACCESS, "invalid code"),
            twoFactorAttempt = true,
        )

        assertTrue(message.contains("código 2FA no es válido"))
        assertTrue(message.contains("invalid code"))
    }

    @Test
    fun appKeySdkErrorDoesNotClaimThatTheUserMustConfigureAKey() {
        val message = MegaErrorMessages.login(
            MegaSdkException(MegaOperation.INITIALIZATION, MegaError.API_EAPPKEY),
            twoFactorAttempt = false,
        )

        assertTrue(message.contains("App Key"))
        assertTrue(message.contains("error de App Key"))
        assertFalse(message.contains("Falta configurar"))
        assertTrue(message.contains("código ${MegaError.API_EAPPKEY}"))
    }
}
