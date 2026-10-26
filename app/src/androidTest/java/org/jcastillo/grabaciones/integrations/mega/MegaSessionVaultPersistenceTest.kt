package org.jcastillo.grabaciones.integrations.mega

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MegaSessionVaultPersistenceTest {
    @Test
    fun aStoresEncryptedSessionForRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = MegaSessionVault(context, TEST_NAMESPACE)
        vault.clear()
        vault.save(TEST_SESSION, TEST_EMAIL)

        assertEquals(TEST_EMAIL, vault.accountEmail())
        assertEquals(TEST_EMAIL, MegaSessionVault(context, TEST_NAMESPACE).accountEmail())
        assertEquals(TEST_SESSION, MegaSessionVault(context, TEST_NAMESPACE).readSession())
        val encryptedFile = File(context.noBackupFilesDir, "mega-session.bin-$TEST_NAMESPACE")
        assertTrue(encryptedFile.isFile)
        assertFalse(encryptedFile.readBytes().toString(Charsets.ISO_8859_1).contains(TEST_SESSION))
    }

    @Test
    fun bRestoresSessionAfterProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restoredVault = MegaSessionVault(context, TEST_NAMESPACE)

        assertEquals(TEST_SESSION, restoredVault.readSession())
        assertEquals(TEST_EMAIL, restoredVault.accountEmail())
        restoredVault.clear()
    }

    private companion object {
        const val TEST_NAMESPACE = "session_restart_smoke"
        const val TEST_SESSION = "opaque-test-session-not-a-real-credential"
        const val TEST_EMAIL = "session-smoke-test@example.invalid"
    }
}
