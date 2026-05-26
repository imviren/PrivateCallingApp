package com.p2p.core.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

// ── Peer entity ────────────────────────────────────────────────────────────────

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val address: String,
    val publicKeyBase64: String? = null
)

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY label ASC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Delete
    suspend fun delete(peer: PeerEntity)
}

// ── Call log entity ────────────────────────────────────────────────────────────

enum class CallType { OUTGOING, INCOMING, MISSED }

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val peerLabel: String,           // display name or address if unknown
    val peerAddress: String,
    val callType: CallType,
    val startedAt: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0L   // 0 for missed/unanswered
)

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CallLogEntity>>

    @Insert
    suspend fun insert(log: CallLogEntity)

    @Delete
    suspend fun delete(log: CallLogEntity)

    @Query("DELETE FROM call_logs")
    suspend fun deleteAll()
}

// ── Database ───────────────────────────────────────────────────────────────────

@Database(
    entities = [PeerEntity::class, CallLogEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun callLogDao(): CallLogDao
}

// ── DI module ─────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "p2p_communication_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun providePeerDao(database: AppDatabase): PeerDao = database.peerDao()

    @Provides
    fun provideCallLogDao(database: AppDatabase): CallLogDao = database.callLogDao()
}
