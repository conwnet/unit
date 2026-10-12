package net.conw.unit.browser

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.ui.Ui

internal class SitePermissions(private val activity: BrowserActivity) {
    private val temporary = mutableMapOf<String, Boolean>()
    private var pending: PermissionRequest? = null

    fun media(request: PermissionRequest) {
        pending?.deny()
        pending = request
        val supported =
            mapOf(
                PermissionRequest.RESOURCE_VIDEO_CAPTURE to
                    (Manifest.permission.CAMERA to activity.getString(R.string.camera)),
                PermissionRequest.RESOURCE_AUDIO_CAPTURE to
                    (Manifest.permission.RECORD_AUDIO to activity.getString(R.string.microphone)),
            )
        val resources = request.resources.filter { it in supported }
        if (resources.isEmpty()) {
            request.deny()
            pending = null
            return
        }
        val runtime = resources.map { supported.getValue(it).first }.toTypedArray()
        val label =
            resources.joinToString(activity.getString(R.string.and)) {
                supported.getValue(it).second
            }
        ask(request.origin.toString(), resources, label) { allowed ->
            if (pending == request) {
                if (!allowed) {
                    request.deny()
                    pending = null
                } else {
                    activity.requests.permissions(runtime) { granted ->
                        if (pending == request) {
                            if (granted) request.grant(resources.toTypedArray()) else request.deny()
                            pending = null
                        }
                    }
                }
            }
        }
    }

    fun canceled(request: PermissionRequest) {
        if (pending == request) pending = null
    }

    fun location(origin: String, callback: GeolocationPermissions.Callback) {
        ask(origin, listOf("location"), activity.getString(R.string.location)) { allowed ->
            if (!allowed) callback.invoke(origin, false, false)
            else {
                activity.requests.permissions(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                ) {
                    // Approximate location alone is a valid Android permission choice.
                    callback.invoke(
                        origin,
                        activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED,
                        false,
                    )
                }
            }
        }
    }

    private fun ask(
        origin: String,
        resources: List<String>,
        label: String,
        callback: (Boolean) -> Unit,
    ) {
        val decisions = resources.map { resource ->
            if (activity.isIncognito) temporary["$origin|$resource"]
            else activity.store.permission(origin, resource)
        }
        if (false in decisions) {
            callback(false)
            return
        }
        if (null !in decisions) {
            callback(true)
            return
        }
        var answered = false
        fun answer(allow: Boolean) {
            answered = true
            resources.forEach { resource ->
                if (activity.isIncognito) temporary["$origin|$resource"] = allow
                else activity.store.permission(origin, resource, allow)
            }
            callback(allow)
        }
        val dialog =
            Ui.dialog(activity)
                .setTitle(R.string.site_permission)
                .setMessage(activity.getString(R.string.permission_message, origin, label))
                .setPositiveButton(R.string.allow) { _, _ -> answer(true) }
                .setNegativeButton(R.string.block) { _, _ -> answer(false) }
                .create()
        dialog.setOnCancelListener { if (!answered) callback(false) }
        activity.showDialog(dialog)
    }

    fun clear() {
        temporary.clear()
        activity.store.clearPermissions()
        GeolocationPermissions.getInstance().clearAll()
    }

    fun destroy() {
        pending?.deny()
        pending = null
    }
}
