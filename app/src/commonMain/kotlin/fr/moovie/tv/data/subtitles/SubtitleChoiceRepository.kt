package fr.moovie.tv.data.subtitles

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.moovie.tv.core.subtitles.model.SubtitleCandidate
import fr.moovie.tv.data.store.preferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Le sous-titre que l'utilisateur a choisi, par titre (clé de lecture).
 *
 * Sam, 09/10/2026 : « quand un utilisateur sélectionne un truc, il faut en
 * tenir compte et il faut le maintenir. [...] Elle ne s'est pas fait chier à
 * le faire pour juste faire joli. » La reprise rejoue ce choix avant de
 * chercher quoi que ce soit ; « Désactivés » se retient aussi.
 *
 * Ce qui est gardé suffit à retrouver le fichier déjà sur disque (identifiant
 * et langue) et à en re-demander le téléchargement s'il a disparu.
 */
class SubtitleChoiceRepository {
    private val store = preferencesStore(STORE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Choix(
        val fileId: String = "",
        val language: String = "",
        val release: String = "",
        val fps: Double? = null,
        /** L'utilisateur a coupé les sous-titres sur ce titre. */
        val off: Boolean = false,
    )

    /** Le choix retenu : [Remembered.Off], un candidat, ou null si rien n'a été choisi. */
    sealed interface Remembered {
        data object Off : Remembered
        data class Candidate(val candidate: SubtitleCandidate) : Remembered
    }

    suspend fun get(key: String): Remembered? {
        val raw = store.data.first()[stringPreferencesKey(key)] ?: return null
        val c = runCatching { json.decodeFromString<Choix>(raw) }.getOrNull() ?: return null
        if (c.off) return Remembered.Off
        if (c.fileId.isBlank() || c.language.isBlank()) return null
        return Remembered.Candidate(SubtitleCandidate(fileId = c.fileId, language = c.language, release = c.release, fps = c.fps))
    }

    suspend fun remember(key: String, candidate: SubtitleCandidate) {
        if (key.isBlank()) return
        val c = Choix(candidate.fileId, candidate.language, candidate.release, candidate.fps)
        store.edit { it[stringPreferencesKey(key)] = json.encodeToString(c) }
    }

    suspend fun rememberOff(key: String) {
        if (key.isBlank()) return
        store.edit { it[stringPreferencesKey(key)] = json.encodeToString(Choix(off = true)) }
    }

    private companion object {
        const val STORE = "moovie_subtitle_choice"
    }
}
