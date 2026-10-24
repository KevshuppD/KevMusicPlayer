package com.kevshupp.kevmusicplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Minimal projection used to match a song against its saved cover file name. */
data class CoverLookupInfo(
    val artist: String,
    val album: String,
    val title: String
)

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio_files ORDER BY title ASC")
    fun getAllAudioFilesFlow(): Flow<List<AudioFile>>

    @Query("SELECT * FROM audio_files ORDER BY title ASC")
    suspend fun getAllAudioFiles(): List<AudioFile>

    @Query("SELECT id, title, artist, album, genre, duration, uriString, folderPath, folderName, NULL as lyrics, NULL as translatedLyrics, playCount, dateAdded, lastPlayed, replayGain, year, dateModified, track FROM audio_files ORDER BY title ASC")
    suspend fun getAllAudioFilesLightweight(): List<AudioFile>

    @Query("SELECT * FROM audio_files ORDER BY title ASC")
    fun getAudioFilesPagingSource(): androidx.paging.PagingSource<Int, AudioFile>

    @Query("SELECT * FROM audio_files WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchAudioFilesPagingSource(query: String): androidx.paging.PagingSource<Int, AudioFile>

    @Query("SELECT * FROM audio_files WHERE id = :id LIMIT 1")
    suspend fun getAudioFileById(id: Long): AudioFile?

    @Query("SELECT lyrics FROM audio_files WHERE id = :id LIMIT 1")
    suspend fun getLyricsById(id: Long): String?

    @Query("SELECT translatedLyrics FROM audio_files WHERE id = :id LIMIT 1")
    suspend fun getTranslatedLyricsById(id: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(audioFiles: List<AudioFile>)

    @Query("DELETE FROM audio_files")
    suspend fun deleteAll()

    @Query("DELETE FROM audio_files WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM audio_files WHERE id NOT IN (:ids)")
    suspend fun keepOnlyIds(ids: List<Long>)

    @Query("DELETE FROM audio_files WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // Updates only the columns that come from a library scan, leaving lyrics, stats and ReplayGain untouched
    @Query("UPDATE audio_files SET title = :title, artist = :artist, album = :album, genre = :genre, duration = :duration, uriString = :uriString, folderPath = :folderPath, folderName = :folderName, dateAdded = :dateAdded, year = :year, dateModified = :dateModified, track = :track WHERE id = :id")
    suspend fun updateScannedMetadata(
        id: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        duration: Long,
        uriString: String,
        folderPath: String,
        folderName: String,
        dateAdded: Long,
        year: String,
        dateModified: Long,
        track: Int
    )

    /** Etiquetas corregidas por "Normalizar biblioteca"; no toca letras ni estadísticas. */
    @Query("UPDATE audio_files SET title = :title, artist = :artist, album = :album, genre = :genre, year = :year, track = :track, dateModified = :dateModified WHERE id = :id")
    suspend fun updateTagMetadata(id: Long, title: String, artist: String, album: String, genre: String, year: String, track: Int, dateModified: Long)

    @Query("UPDATE audio_files SET replayGain = :replayGain WHERE id = :id")
    suspend fun updateReplayGain(id: Long, replayGain: Float)

    /**
     * Firma de la biblioteca (número de canciones, suma de ids y longitud de título/artista/álbum); emite de nuevo con
     * cada escritura en la tabla. KevMusic Desktop la usa para detectar altas, bajas, ids nuevos (organizar o migrar
     * a otro volumen) y etiquetas editadas; las reproducciones no la cambian.
     */
    @Query("SELECT COUNT(*) || ':' || COALESCE(SUM(id), 0) || ':' || COALESCE(SUM(LENGTH(title) + LENGTH(artist) + LENGTH(album)), 0) FROM audio_files")
    fun observeLibrarySignature(): Flow<String>

    @Query("SELECT COUNT(*) FROM audio_files WHERE lyrics IS NOT NULL AND TRIM(lyrics) != ''")
    suspend fun countSongsWithLyrics(): Int

    // Blocking variant for the album art loader, which already runs on a background thread
    @Query("SELECT artist, album, title FROM audio_files WHERE id = :id LIMIT 1")
    fun getCoverLookupInfoBlocking(id: Long): CoverLookupInfo?

    @Query("UPDATE audio_files SET lyrics = :lyrics WHERE id = :id")
    suspend fun updateLyrics(id: Long, lyrics: String?)

    @Query("UPDATE audio_files SET translatedLyrics = :translatedLyrics WHERE id = :id")
    suspend fun updateTranslatedLyrics(id: Long, translatedLyrics: String?)

    @Query("UPDATE audio_files SET lyrics = NULL, translatedLyrics = NULL")
    suspend fun deleteAllLyrics()

    @Query("UPDATE audio_files SET playCount = 0, lastPlayed = 0")
    suspend fun resetAllPlayStats()

    @Query("UPDATE audio_files SET playCount = :playCount, lastPlayed = :lastPlayed WHERE id = :id")
    suspend fun setPlayStats(id: Long, playCount: Int, lastPlayed: Long)

    @Query("SELECT id, lyrics, translatedLyrics FROM audio_files WHERE id = :id")
    suspend fun getLyricsOnly(id: Long): StoredLyrics?

    @Query("UPDATE audio_files SET playCount = playCount + 1, lastPlayed = :timestamp WHERE id = :id")
    suspend fun incrementPlayCount(id: Long, timestamp: Long)
}

data class StoredLyrics(val id: Long, val lyrics: String?, val translatedLyrics: String?)
