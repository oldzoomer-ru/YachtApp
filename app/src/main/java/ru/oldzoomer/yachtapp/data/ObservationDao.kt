package ru.oldzoomer.yachtapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservationDao {

    @Query("SELECT * FROM observations ORDER BY timestamp DESC")
    fun getAllObservations(): Flow<List<Observation>>

    @Query("SELECT * FROM observations WHERE id = :id")
    suspend fun getObservationById(id: Long): Observation?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertObservation(observation: Observation)

    @Delete
    suspend fun deleteObservation(observation: Observation)

    @Query("DELETE FROM observations")
    suspend fun clearAll()

    @Transaction
    suspend fun clearAndInsert(observations: List<Observation>) {
        clearAll()
        observations.forEach { insertObservation(it) }
    }
}
