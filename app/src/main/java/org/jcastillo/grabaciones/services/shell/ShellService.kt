/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.jcastillo.grabaciones.services.shell

import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import org.jcastillo.grabaciones.ILogCallback
import org.jcastillo.grabaciones.IRecordingCallback
import org.jcastillo.grabaciones.IShellService
import org.jcastillo.grabaciones.data.recording.RecordingPlan
import org.jcastillo.grabaciones.services.shell.recording.ShellRecordingEngine
import org.jcastillo.grabaciones.services.shell.utils.ShellWorkarounds
import org.jcastillo.grabaciones.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * ShellService runs inside the Android shell UID (2000), launched by this app's embedded ADB client.
 * The class is kept by R8 because app_process creates it outside Android's normal application entry points.
 */
@Keep
class ShellService : IShellService.Stub() {

    private val isInitialized = AtomicBoolean(false)
    private val recordingEngine by lazy { ShellRecordingEngine() }
    private val commandExecutor by lazy { ShellCommandExecutor() }

    /**
     * Ensure the shell service thread is initialized. Should generally be called in every method.
     *
     * Note: We do not use init because we prefer to have AppLogger to be initialized as remote to send backlogs,
     * which is impossible inside init scope
     */
    private fun ensureInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            AppLogger.d("ShellService is running its first initialization, running as UID ${android.os.Process.myUid()}")
            ShellWorkarounds.apply()
            AppLogger.d("Applied workarounds to replicate a valid app (shell) ActivityThread context")
        } else {
            AppLogger.d("ShellService already initialized")
        }
    }

    // -------- IShellService AIDL implementation

    override fun setLogCallback(listener: ILogCallback, isRedactionEnabled: Boolean) = clearIdentity {
        AppLogger.initAsRemote(listener, isRedactionEnabled)
        ensureInitialized() // Call after so AppLogger can send output via IPC
    }

    override fun canCaptureAudioOutput(): Boolean = clearIdentity {
        ensureInitialized()
        val systemContext = ShellWorkarounds.getSystemContext()
        android.os.Process.myUid() == 2000 && systemContext?.checkPermission(
            "android.permission.CAPTURE_AUDIO_OUTPUT",
            android.os.Process.myPid(),
            android.os.Process.myUid()
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun startRecording(
        outputFile: ParcelFileDescriptor,
        plan: RecordingPlan,
        callback: IRecordingCallback
    ) = clearIdentity {
        ensureInitialized()
        recordingEngine.start(
            outputFile = outputFile,
            plan = plan,
            callback = callback
        )
    }

    override fun stopRecording() = clearIdentity {
        ensureInitialized()
        recordingEngine.stop()
    }

    override fun pauseRecording() = clearIdentity {
        ensureInitialized()
        recordingEngine.pause()
    }

    override fun resumeRecording() = clearIdentity {
        ensureInitialized()
        recordingEngine.resume()
    }

    override fun grantAppOpByPackage(packageName: String, opName: String, userProfileId: Int): Boolean = clearIdentity {
        ensureInitialized()
        return commandExecutor.grantAppOpByPackage(packageName, opName, userProfileId)
    }

    override fun grantAppOpByUid(uid: Int, opName: String, userProfileId: Int): Boolean = clearIdentity {
        ensureInitialized()
        return commandExecutor.grantAppOpByUid(uid, opName, userProfileId)
    }

    override fun grantRole(packageName: String, roleName: String, userProfileId: Int): Boolean = clearIdentity {
        ensureInitialized()
        return commandExecutor.grantRole(packageName, roleName, userProfileId)
    }


    /**
     * Terminates the detached embedded-ADB shell daemon when explicitly requested.
     * @see IShellService.destroy
     */
    override fun destroy() {
        AppLogger.i("ShellService.destroy() – terminating shell process")
        stopRecording()
        exitProcess(0)
    }

    /**
     * Clears the calling identity and work source for the duration of the provided block, restoring them afterward.
     *
     * @param block The code block to execute with cleared identity and work source.
     * @return The result of the block execution.
     */
    private inline fun <T> clearIdentity(block: () -> T): T {
        /*
            NOTE: This is not always needed but here to be safe. Without it, AudioRecord createRecord() log warnings
            about mismatching UID ("createRecord uid 2000 tried to pass itself off as 10176") on AOSP, which may cause failure on other OEMs.
            In other cases, some permission checks fully fails, for example, for the AudioPlayback workaround via AudioPolicy, createAudioRecordSink
            checks the calling UID at a lower-level and fails.
         */
        val identityToken = Binder.clearCallingIdentity()
        val workSourceToken = Binder.clearCallingWorkSource()
        try {
            return block()
        } finally {
            Binder.restoreCallingWorkSource(workSourceToken)
            Binder.restoreCallingIdentity(identityToken)
        }
    }
}