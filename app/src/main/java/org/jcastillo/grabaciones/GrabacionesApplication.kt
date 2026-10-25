/*
 * Grabaciones Locales application entry point.
 * Original reused sources retain their respective copyright notices.
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.jcastillo.grabaciones

import android.app.Application
import org.jcastillo.grabaciones.services.callDetection.CallDetectionOrchestrator
import org.jcastillo.grabaciones.utils.AppLogger
import org.jcastillo.grabaciones.data.recording.LocalRecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ShizuApplication is run when the app process is created. Can be seen as the very first entry point of the app.
 */
class GrabacionesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        // Sync configurations down to PackageManager mapping immediately on launch
        CallDetectionOrchestrator(applicationContext).syncComponents()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { LocalRecordingRepository(applicationContext).recoverPending() }
                .onFailure { AppLogger.e("Could not recover an earlier WAV intermediate", it) }
        }
    }
}