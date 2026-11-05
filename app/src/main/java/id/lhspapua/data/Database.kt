package id.lhspapua.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="calls") data class CallEntity(@PrimaryKey val id: String, val date: String, val sales: String, val payload: String)
@Entity(tableName="daily") data class DailyEntity(@PrimaryKey val id: String, val date: String, val sales: String, val payload: String)
@Entity(tableName="settings") data class SettingsEntity(@PrimaryKey val id: Int = 1, val payload: String)
@Dao interface ReportDao {
    @Query("SELECT * FROM calls ORDER BY date DESC, rowid DESC") fun calls(): Flow<List<CallEntity>>
    @Query("SELECT * FROM daily ORDER BY date DESC, rowid DESC") fun daily(): Flow<List<DailyEntity>>
    @Query("SELECT * FROM calls") suspend fun allCalls(): List<CallEntity>
    @Query("SELECT * FROM daily") suspend fun allDaily(): List<DailyEntity>
    @Query("SELECT * FROM calls WHERE id=:id") suspend fun call(id: String): CallEntity?
    @Query("SELECT * FROM daily WHERE id=:id") suspend fun day(id: String): DailyEntity?
    @Upsert suspend fun save(entity: CallEntity)
    @Upsert suspend fun save(entity: DailyEntity)
    @Upsert suspend fun save(entity: SettingsEntity)
    @Query("SELECT * FROM settings WHERE id=1") suspend fun settings(): SettingsEntity?
    @Query("DELETE FROM calls WHERE id=:id") suspend fun deleteCall(id: String)
    @Query("DELETE FROM daily WHERE id=:id") suspend fun deleteDaily(id: String)
}
@Database(entities=[CallEntity::class,DailyEntity::class,SettingsEntity::class], version=1, exportSchema=false)
abstract class LhsDatabase: RoomDatabase() {
    abstract fun dao(): ReportDao
    companion object {
        @Volatile private var instance: LhsDatabase? = null
        fun get(context: Context): LhsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,LhsDatabase::class.java,"lhs-papua.db").build().also { instance=it }
        }
    }
}
