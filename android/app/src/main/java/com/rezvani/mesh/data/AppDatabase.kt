package com.rezvani.mesh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rezvani.mesh.data.dao.ChannelDao
import com.rezvani.mesh.data.dao.ContactDao
import com.rezvani.mesh.data.dao.MessageDao
import com.rezvani.mesh.data.entities.ChannelEntity
import com.rezvani.mesh.data.entities.ContactEntity
import com.rezvani.mesh.data.entities.MessageEntity
import net.sqlcipher.database.SupportFactory

/**
 * Main Room database for Rezvan Mesh.
 * Encrypted using SQLCipher with a passphrase derived from Android Keystore.
 */
@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        ChannelEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DATABASE_NAME = "rezvan_mesh.db"
        private const val KEY_ALIAS = "rezvan_db_key"

        /**
         * Gets the database instance.
         *
         * @param context Application context.
         * @param passphrase Database encryption passphrase (derived from Keystore).
         */
        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val factory = SupportFactory(passphrase)
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(factory)
                    .build()
                INSTANCE = db
                db
            }
        }

        /**
         * Closes and clears the database instance (for testing or reset).
         */
        fun destroyInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
