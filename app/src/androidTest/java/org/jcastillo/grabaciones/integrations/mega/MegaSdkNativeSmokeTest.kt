package org.jcastillo.grabaciones.integrations.mega

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import nz.mega.sdk.MegaApiAndroid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MegaSdkNativeSmokeTest {
    @Test
    fun officialMegaJniLibraryInitializesWithNullAppKeyWithoutAnAccountSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sdkPath = File(context.filesDir, "mega_sdk_smoke_test").apply { mkdirs() }
        val api = MegaApiAndroid(null, "GrabacionesLocales SDK smoke test", "${sdkPath.absolutePath}/")

        assertTrue("The SDK storage directory should be created", sdkPath.isDirectory)
        assertEquals("A new SDK instance must not log in implicitly", 0, api.isLoggedIn())
    }
}
