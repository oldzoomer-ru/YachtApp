package ru.oldzoomer.yachtapp.data

import kotlinx.coroutines.flow.Flow

interface WeatherRepository {
    fun getAllObservations(): Flow<List<Observation>>
    suspend fun getObservation(id: Long): Observation?
    suspend fun insertObservation(observation: Observation)
    suspend fun deleteObservation(observation: Observation)
    suspend fun clearAllObservations()
}

class WeatherRepositoryImpl(
    private val observationDao: ObservationDao
) : WeatherRepository {

    override fun getAllObservations(): Flow<List<Observation>> =
        observationDao.getAllObservations()

    override suspend fun getObservation(id: Long): Observation? =
        observationDao.getObservationById(id)

    override suspend fun insertObservation(observation: Observation) =
        observationDao.insertObservation(observation)

    override suspend fun deleteObservation(observation: Observation) =
        observationDao.deleteObservation(observation)

    override suspend fun clearAllObservations() =
        observationDao.clearAll()
}
