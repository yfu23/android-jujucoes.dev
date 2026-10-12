package com.qrcodejuju.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val FOLDER_NAME = "JujuCodes"
private const val FILE_NAME = "jujucodes-data.enc"
private const val PREFS = "jujucodes_sync_state"

sealed class SyncOutcome {
    data object Done : SyncOutcome()
    /** Drive access needs to be (re-)granted; the UI should send the user through Settings. */
    data object NeedsConsent : SyncOutcome()
    /** Local and remote both have data the other doesn't know about; the UI must ask the user which wins. */
    data class Conflict(val remoteCodes: List<SavedCode>) : SyncOutcome()
    data class Error(val message: String) : SyncOutcome()
}

enum class ConflictChoice { USE_REMOTE, KEEP_LOCAL }

/**
 * Owns the "JujuCodes" Drive folder + its one encrypted data file, and the small amount of
 * local state needed to sync it (which file, and what we last saw there). Drive mode treats
 * the Drive file as the source of truth; the local Room table is a cache kept in step with it.
 */
class SyncManager(private val context: Context, private val dao: SavedCodeDao) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var driveModeEnabled: Boolean
        get() = prefs.getBoolean("driveMode", false)
        private set(v) = prefs.edit().putBoolean("driveMode", v).apply()

    private var folderId: String?
        get() = prefs.getString("folderId", null)
        set(v) = prefs.edit().putString("folderId", v).apply()

    private var fileId: String?
        get() = prefs.getString("fileId", null)
        set(v) = prefs.edit().putString("fileId", v).apply()

    private var knownRemoteModifiedTime: String?
        get() = prefs.getString("remoteModifiedTime", null)
        set(v) = prefs.edit().putString("remoteModifiedTime", v).apply()

    private var localDirty: Boolean
        get() = prefs.getBoolean("localDirty", false)
        set(v) = prefs.edit().putBoolean("localDirty", v).apply()

    var lastSyncedAt: Long
        get() = prefs.getLong("lastSyncedAt", 0)
        private set(v) = prefs.edit().putLong("lastSyncedAt", v).apply()

    /** Call after any local save/rename/delete while Drive mode is on. */
    fun markLocalDirty() {
        if (driveModeEnabled) localDirty = true
    }

    /**
     * Sets up Drive mode using an already-granted [accessToken]: finds or creates the
     * JujuCodes folder, then either creates its data file (if this is the first device to
     * sync) or, if one already exists, returns [SyncOutcome.Conflict] so the caller can ask
     * the user whether to adopt Drive's data or keep what's on this phone.
     */
    suspend fun continueEnable(accessToken: String): SyncOutcome = withContext(Dispatchers.IO) {
        try {
            val api = DriveApi(accessToken)
            val folder = api.findOrCreateFolder(FOLDER_NAME)
            folderId = folder
            val existing = api.findFile(FILE_NAME, folder)
            if (existing == null) {
                val local = dao.allIncludingDeleted()
                val bytes = SyncCrypto.encrypt(SyncCrypto.getOrCreateKey(context), encodeCodes(local))
                val (newFileId, modifiedTime) = api.createFile(FILE_NAME, folder, bytes)
                fileId = newFileId
                knownRemoteModifiedTime = modifiedTime
                driveModeEnabled = true
                localDirty = false
                lastSyncedAt = System.currentTimeMillis()
                SyncOutcome.Done
            } else {
                val (existingFileId, modifiedTime) = existing
                fileId = existingFileId
                val remoteBytes = api.downloadFile(existingFileId)
                val remoteCodes = decodeCodes(SyncCrypto.decrypt(SyncCrypto.getOrCreateKey(context), remoteBytes))
                val localHasData = dao.allIncludingDeleted().any { it.deletedAt == null }
                knownRemoteModifiedTime = modifiedTime
                if (localHasData) {
                    SyncOutcome.Conflict(remoteCodes)
                } else {
                    dao.replaceAll(remoteCodes)
                    driveModeEnabled = true
                    localDirty = false
                    lastSyncedAt = System.currentTimeMillis()
                    SyncOutcome.Done
                }
            }
        } catch (e: Exception) {
            SyncOutcome.Error(describeError(e))
        }
    }

    /** Resolves the conflict from [continueEnable]'s initial setup. */
    suspend fun resolveInitialConflict(choice: ConflictChoice, remoteCodes: List<SavedCode>): SyncOutcome =
        withContext(Dispatchers.IO) {
            driveModeEnabled = true
            lastSyncedAt = System.currentTimeMillis()
            when (choice) {
                ConflictChoice.USE_REMOTE -> {
                    dao.replaceAll(remoteCodes)
                    localDirty = false
                    SyncOutcome.Done
                }
                ConflictChoice.KEEP_LOCAL -> {
                    localDirty = true
                    push()
                }
            }
        }

    /** Uploads the current local dataset. Call after local writes (the caller should debounce). */
    suspend fun push(): SyncOutcome = withContext(Dispatchers.IO) {
        val folder = folderId
        val file = fileId
        if (!driveModeEnabled || folder == null || file == null) {
            return@withContext SyncOutcome.Error("Drive sync isn't set up yet")
        }
        try {
            val token = when (val auth = DriveAuth.authorize(context)) {
                is DriveAuthResult.Granted -> auth.accessToken
                is DriveAuthResult.NeedsConsent -> return@withContext SyncOutcome.NeedsConsent
                is DriveAuthResult.Failed -> return@withContext SyncOutcome.Error(auth.message)
            }
            val api = DriveApi(token)
            val remoteModifiedTime = api.fileModifiedTime(file)
            if (remoteModifiedTime != knownRemoteModifiedTime) {
                // Another device changed it since we last synced -- don't clobber its changes.
                val remoteCodes = decodeCodes(SyncCrypto.decrypt(SyncCrypto.getOrCreateKey(context), api.downloadFile(file)))
                return@withContext SyncOutcome.Conflict(remoteCodes)
            }
            val local = dao.allIncludingDeleted()
            val bytes = SyncCrypto.encrypt(SyncCrypto.getOrCreateKey(context), encodeCodes(local))
            knownRemoteModifiedTime = api.updateFile(file, bytes)
            localDirty = false
            lastSyncedAt = System.currentTimeMillis()
            SyncOutcome.Done
        } catch (e: Exception) {
            SyncOutcome.Error(describeError(e))
        }
    }

    /**
     * Downloads the latest remote data. If this phone also has unsynced local edits, returns
     * a [SyncOutcome.Conflict] instead of silently overwriting them.
     */
    suspend fun pull(): SyncOutcome = withContext(Dispatchers.IO) {
        val folder = folderId
        val file = fileId
        if (!driveModeEnabled || folder == null || file == null) {
            return@withContext SyncOutcome.Error("Drive sync isn't set up yet")
        }
        try {
            val token = when (val auth = DriveAuth.authorize(context)) {
                is DriveAuthResult.Granted -> auth.accessToken
                is DriveAuthResult.NeedsConsent -> return@withContext SyncOutcome.NeedsConsent
                is DriveAuthResult.Failed -> return@withContext SyncOutcome.Error(auth.message)
            }
            val api = DriveApi(token)
            val modifiedTime = api.fileModifiedTime(file)
            if (modifiedTime == knownRemoteModifiedTime && !localDirty) return@withContext SyncOutcome.Done
            if (modifiedTime == knownRemoteModifiedTime && localDirty) return@withContext push()
            val remoteCodes = decodeCodes(SyncCrypto.decrypt(SyncCrypto.getOrCreateKey(context), api.downloadFile(file)))
            if (localDirty) return@withContext SyncOutcome.Conflict(remoteCodes)
            dao.replaceAll(remoteCodes)
            knownRemoteModifiedTime = modifiedTime
            lastSyncedAt = System.currentTimeMillis()
            SyncOutcome.Done
        } catch (e: Exception) {
            SyncOutcome.Error(describeError(e))
        }
    }

    /** Resolves a conflict surfaced by [push] or [pull]. */
    suspend fun resolveSyncConflict(choice: ConflictChoice, remoteCodes: List<SavedCode>): SyncOutcome =
        when (choice) {
            ConflictChoice.USE_REMOTE -> withContext(Dispatchers.IO) {
                dao.replaceAll(remoteCodes)
                localDirty = false
                lastSyncedAt = System.currentTimeMillis()
                SyncOutcome.Done
            }
            ConflictChoice.KEEP_LOCAL -> push()
        }

    /** Switches back to local-only. This phone's cache stays; whatever is in Drive is left untouched. */
    fun disableDriveMode() {
        driveModeEnabled = false
        localDirty = false
    }

    private fun describeError(e: Exception) = e.message ?: "Couldn't reach Google Drive"

    private fun encodeCodes(codes: List<SavedCode>): ByteArray {
        val arr = JSONArray()
        for (c in codes) arr.put(
            JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("text", c.text)
                put("format", c.format)
                put("createdAt", c.createdAt)
                put("updatedAt", c.updatedAt)
                put("deletedAt", c.deletedAt ?: JSONObject.NULL)
            }
        )
        return JSONObject().apply { put("version", 1); put("codes", arr) }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeCodes(bytes: ByteArray): List<SavedCode> {
        val arr = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("codes")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedCode(
                id = o.getString("id"),
                name = o.optString("name", ""),
                text = o.getString("text"),
                format = o.getString("format"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
                deletedAt = if (o.isNull("deletedAt")) null else o.getLong("deletedAt"),
            )
        }
    }
}
