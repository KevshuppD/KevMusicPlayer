package com.kevshupp.kevmusicplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio_files ORDER BY title ASC")
    fun getAllAudioFilesFlow(): Flow<List<AudioFile>>

    @Query("SELECT * FROM audio_files ORDER BY title ASC")
    suspend fun getAllAudioFiles(): List<AudioFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(audioFiles: List<AudioFile>)

    @Query("DELETE FROM audio_files")
    suspend fun deleteAll()

    @Query("DELETE FROM audio_files WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM audio_files WHERE id NOT IN (:ids)")
    suspend fun keepOnlyIds(ids: List<Long>)

    @Query("UPDATE audio_files SET lyrics = :lyrics WHERE id = :id")
    suspend fun updateLyrics(id: Long, lyrics: String?)

    @Query("UPDATE audio_files SET translatedLyrics = :translatedLyrics WHERE id = :id")
    suspend fun updateTranslatedLyrics(id: Long, translatedLyrics: String?)
}
