package org.jcastillo.grabaciones;

import android.os.ParcelFileDescriptor;
import org.jcastillo.grabaciones.ILogCallback;
import org.jcastillo.grabaciones.IRecordingCallback;
import org.jcastillo.grabaciones.data.recording.RecordingPlan;

interface IShellService {
    /**
     * Set the logger callback for the AppLogger.
     * Every logs message from the service will be sent to this callback.
     * Allow inter-process logging from the service to the app.
     *
     * @param appLoggerCallback The callback interface for logging messages.
     * @param isRedactionEnabled If true, sensitive information in logs will be redacted.
     */
    void setLogCallback(
        ILogCallback appLoggerCallback,
        boolean isRedactionEnabled
    ) = 1;

    /** True only if the Binder host runs as shell and has the platform audio-capture permission. */
    boolean canCaptureAudioOutput() = 9;

    /**
     * Starts a recording session.
     *
     * The application provides the already-open output file descriptor,
     * the shell process owns the capture/processing/encoding/
     * output pipeline for the lifetime of the recording.
     *
     * @param outputFile The writable descriptor for the recording file.
     * @param plan The recording configuration.
     * @param callback The callback interface for recording events.
     */
    void startRecording(
        in ParcelFileDescriptor outputFile,
        in RecordingPlan plan,
        in IRecordingCallback callback
    ) = 2;

    /**
     * Stops the active recording session and finalizes the output.
    */
    void stopRecording() = 3;

    /**
     * Pauses the active recording session.
    */
    void pauseRecording() = 4;

    /**
     * Resumes the active recording session.
    */
    void resumeRecording() = 5;

    /**
     * Grants an AppOp permission at the package level for a specific user profile.
     * @param packageName The package name of the app to grant the permission to.
     * @param opName The AppOp operation name (e.g., "READ_PHONE_STATE").
     * @param userProfileId The user ID for which to grant the permission  (e.g. 0 for the primary user)..
     * @return True if the command was successful (exit code 0 and no error output), false otherwise.
    */
    boolean grantAppOpByPackage(String packageName, String opName, int userProfileId) = 6;
    /**
     * Grants an AppOp permission at the app UID level (prioritized over package value) for a specific user profile.
     * @param uid The UID of the app to grant the permission to.
     * @param opName The AppOp operation name (e.g., "READ_PHONE_STATE").
     * @param userProfileId The user ID for which to grant the permission.
     * @return True if the command was successful, false otherwise.
    */
    boolean grantAppOpByUid(int uid, String opName, int userProfileId) = 7;
    /**
     * Grants a role to a package in a specific user profile.
     * @param packageName The package name of the app to grant the role to.
     * @param roleName The name of the role to grant (e.g., "DIALER").
     * @param userProfileId The user ID for which to grant the role.
     * @return True if the command was successful, false otherwise.
    */
    boolean grantRole(String packageName, String roleName, int userProfileId) = 8;

    /**
     * Terminates the embedded-ADB recorder daemon after stopping any active capture.
     */
    void destroy() = 10;
}
