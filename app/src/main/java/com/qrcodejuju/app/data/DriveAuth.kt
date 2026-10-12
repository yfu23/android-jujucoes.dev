package com.qrcodejuju.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

private val DRIVE_FILE_SCOPE = Scope("https://www.googleapis.com/auth/drive.file")

sealed class DriveAuthResult {
    data class Granted(val accessToken: String) : DriveAuthResult()
    data class NeedsConsent(val intentSender: IntentSender) : DriveAuthResult()
    data class Failed(val message: String) : DriveAuthResult()
}

/**
 * Wraps Google Play Services' Authorization API (the current, non-deprecated way to ask for
 * an API scope like Drive -- separate from signing the user in). The first call usually
 * returns [DriveAuthResult.NeedsConsent]; after the user grants it once, later calls from
 * this same account resolve silently with a fresh [DriveAuthResult.Granted] access token,
 * no UI needed, which is what lets background syncs happen without interrupting the user.
 */
object DriveAuth {
    private fun request() = AuthorizationRequest.Builder().setRequestedScopes(listOf(DRIVE_FILE_SCOPE)).build()

    suspend fun authorize(context: Context): DriveAuthResult = try {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (result.hasResolution()) {
            val sender = result.pendingIntent?.intentSender
            if (sender != null) DriveAuthResult.NeedsConsent(sender)
            else DriveAuthResult.Failed("Google didn't offer a way to grant access")
        } else {
            val token = result.accessToken
            if (token != null) DriveAuthResult.Granted(token)
            else DriveAuthResult.Failed("Google didn't return an access token")
        }
    } catch (e: Exception) {
        DriveAuthResult.Failed(e.message ?: "Couldn't reach Google")
    }

    /** Call from the consent screen's ActivityResult callback with its result Intent. */
    fun fromIntent(context: Context, data: Intent): DriveAuthResult = try {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        result.accessToken?.let { DriveAuthResult.Granted(it) }
            ?: DriveAuthResult.Failed("Google Drive access wasn't granted")
    } catch (e: Exception) {
        DriveAuthResult.Failed(e.message ?: "Authorization failed")
    }
}
