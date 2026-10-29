package com.kurdistan.iptv

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Google Play's own full-screen update, inside the app (the Google Play
 * build; the GitHub build carries it too but installs its own apk instead).
 * Made while the activity is created, as Android wants for its result.
 * `say` hands the page its state: "cancel" or "noplay".
 */
internal class StoreUpdate(private val activity: ComponentActivity, private val say: (String) -> Unit) {
    private val manager by lazy { AppUpdateManagerFactory.create(activity) }
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> if (r.resultCode != Activity.RESULT_OK) say("cancel") }

    fun run(resumeOnly: Boolean = false) {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                val avail = info.updateAvailability()
                val go = avail == UpdateAvailability.UPDATE_AVAILABLE ||
                    avail == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                if (resumeOnly && avail != UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
                    return@addOnSuccessListener
                if (go && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    try {
                        manager.startUpdateFlowForResult(
                            info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                        )
                    } catch (e: Exception) { say("noplay") }
                } else if (!resumeOnly) say("noplay")
            }
            .addOnFailureListener { if (!resumeOnly) say("noplay") }
    }

    companion object {
        /** true only in the Amazon build, whose updates come from the Amazon Appstore */
        const val STORE_ONLY = false
    }
}
