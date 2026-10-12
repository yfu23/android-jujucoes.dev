package com.qrcodejuju.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * [format] is the name of a ZXing BarcodeFormat, e.g. "QR_CODE". The image is regenerated
 * from text + format.
 *
 * [id] is a UUID (not auto-increment) so codes created on different phones never collide
 * when synced through Google Drive. [updatedAt] and [deletedAt] exist for the same reason:
 * [updatedAt] lets a sync pick the newer side of a conflict, and deletes are soft (a
 * tombstone timestamp) so a delete on one phone can reach another instead of silently
 * reverting the next time they sync.
 */
@Entity(tableName = "saved_codes")
data class SavedCode(
    @PrimaryKey val id: String,
    val name: String = "",
    val text: String,
    val format: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

@Dao
interface SavedCodeDao {
    @Query("SELECT * FROM saved_codes WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun all(): Flow<List<SavedCode>>

    /** Includes tombstones -- used when building the snapshot pushed to Drive. */
    @Query("SELECT * FROM saved_codes")
    suspend fun allIncludingDeleted(): List<SavedCode>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(code: SavedCode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(codes: List<SavedCode>)

    @Query("DELETE FROM saved_codes")
    suspend fun deleteAllRows()

    /** Makes local data exactly match [codes], e.g. after choosing "use Drive's data". */
    @Transaction
    suspend fun replaceAll(codes: List<SavedCode>) {
        deleteAllRows()
        upsertAll(codes)
    }

    @Query("DELETE FROM saved_codes WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeTombstonesOlderThan(before: Long)
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE saved_codes ADD COLUMN name TEXT NOT NULL DEFAULT ''")
    }
}

/** Switches the primary key from an auto-increment Long to a UUID string, and adds sync bookkeeping columns. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE saved_codes_new (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                text TEXT NOT NULL,
                format TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            )"""
        )
        db.execSQL(
            """INSERT INTO saved_codes_new (id, name, text, format, createdAt, updatedAt, deletedAt)
               SELECT lower(hex(randomblob(16))), name, text, format, createdAt, createdAt, NULL
               FROM saved_codes"""
        )
        db.execSQL("DROP TABLE saved_codes")
        db.execSQL("ALTER TABLE saved_codes_new RENAME TO saved_codes")
    }
}

@Database(entities = [SavedCode::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): SavedCodeDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "qrcodejuju.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
