package com.p2p.core.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
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

@Database(entities = [PeerEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
}

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
    fun providePeerDao(database: AppDatabase): PeerDao {
        return database.peerDao()
    }
}
