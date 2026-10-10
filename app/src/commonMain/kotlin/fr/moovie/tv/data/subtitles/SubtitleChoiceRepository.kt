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

    /**
     * Les sous-titres déjà activés sur ce titre, par identifiant de fichier.
     *
     * Sam, 10/10/2026 : « il faudrait pouvoir les disqualifier au fur et à
     * mesure qu'on les teste, et ne pas être là à chaque fois à revenir dans
     * le menu et se dire "celui-là, est-ce que je l'ai testé ou pas ?" ». Le
     * menu les marque et les range après ceux qu'on n'a pas encore essayés ;
     * ça survit à la sortie du lecteur, la mémoire des noms de release ne
     * survivant, elle, à rien.
     */
    suspend fun tried(key: String): Set<String> {
        if (key.isBlank()) return emptySet()
        return store.data.first()[stringPreferencesKey(TRIED + key)]
            ?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    }

    suspend fun markTried(key: String, fileId: String) {
        if (key.isBlank() || fileId.isBlank()) return
        store.edit { prefs ->
            val k = stringPreferencesKey(TRIED + key)
            val actuels = prefs[k]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            if (fileId !in actuels) prefs[k] = (actuels + fileId).takeLast(PLAFOND_ESSAYES).joinToString(",")
        }
    }

    private companion object {
        const val STORE = "moovie_subtitle_choice"
        const val TRIED = "tried:"
        const val PLAFOND_ESSAYES = 60
    }
}
