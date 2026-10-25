/*
 * Embedded-ADB Binder delivery, adapted from CallVault's GPL-3.0-or-later implementation.
 * Its direct ContentProvider path mirrors RikkaApps/Shizuku's Apache-2.0 server mechanism.
 * See THIRD_PARTY_NOTICES.md.
 */
package org.jcastillo.grabaciones.server

import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log

/** Hands an app_process Binder to our exported provider without misattributing shell as package android. */
internal object EmbeddedAdbBinderDelivery {
    private const val TAG = "GL:BinderDelivery"
    private const val USER_SYSTEM = 0

    fun deliver(binder: IBinder, authority: String): Boolean {
        val extras = Bundle().apply { putBinder(EmbeddedAdbBinderProvider.EXTRA_BINDER, binder) }
        return runCatching { deliverViaActivityManager(authority, extras) }
            .onFailure { Log.e(TAG, "ActivityManager provider delivery failed", it) }
            .getOrDefault(false)
    }

    /** Uses the shell process's own attribution, not the system-context ContentResolver's `android` package. */
    private fun deliverViaActivityManager(authority: String, extras: Bundle): Boolean {
        val activityManager = Class.forName("android.app.ActivityManager")
            .getMethod("getService")
            .invoke(null) ?: error("ActivityManager.getService() returned null")
        val managerClass = activityManager.javaClass
        val getProvider = managerClass.methods.firstOrNull {
            it.name == "getContentProviderExternal" && it.parameterTypes.size == 4
        } ?: error("getContentProviderExternal(String,int,IBinder,String) is unavailable")
        val holder = getProvider.invoke(activityManager, authority, USER_SYSTEM, null, authority)
            ?: error("getContentProviderExternal returned no holder for $authority")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
                ?: error("ContentProviderHolder.provider is null")
            val attribution = buildAttributionSource()
            val call = provider.javaClass.methods.firstOrNull {
                it.name == "call" && it.parameterTypes.size == 5 &&
                    it.parameterTypes[0].name == "android.content.AttributionSource"
            } ?: error("IContentProvider.call(AttributionSource,...) is unavailable")
            val reply = call.invoke(provider, attribution, authority, EmbeddedAdbBinderProvider.METHOD_SEND_BINDER, null, extras) as? Bundle
            return reply?.getBoolean(EmbeddedAdbBinderProvider.EXTRA_ACCEPTED) == true
        } finally {
            runCatching {
                managerClass.methods.firstOrNull {
                    it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2
                }?.invoke(activityManager, authority, null)
            }.onFailure { Log.w(TAG, "Could not release external provider reference", it) }
        }
    }

    private fun buildAttributionSource(): Any {
        val builderClass = Class.forName("android.content.AttributionSource\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(Process.myUid())
        builderClass.getMethod("setPackageName", String::class.java).invoke(builder, null)
        return builderClass.getMethod("build").invoke(builder) ?: error("AttributionSource.Builder.build() returned null")
    }
}
