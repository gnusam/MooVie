package fr.moovie.tv.data.sources

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.moovie.tv.data.store.preferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Ce qu'on a appris des pistes audio d'une source **en la jouant**.
 *
 * Un nom de release ne dit pas tout : *La cocinera de Castamar* en
 * « MultiLang » (10/10/2026) portait l'allemand, l'anglais, le français, le
 * turc et une audiodescription espagnole — pas la VO. Rien ne permettait de le
 * savoir avant d'ouvrir le fichier ; une fois ouvert, le lecteur le sait, et
 * ce serait dommage de le réapprendre à chaque épisode du pack.
 *
 * Mémoire par identité de source (voir `sourceIdentity`), hors sauvegarde :
 * c'est une connaissance locale, et un infohash n'a de sens que pour qui a
 * l'addon.
 */
class SourceAudioRepository {

    private val store = preferencesStore(STORE)

    /** Les sources dont on sait qu'elles n'ont pas de piste dans la langue d'origine. */
    val sansVo: Flow<Set<String>> = store.data.map { prefs ->
        prefs[SANS_VO]?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    }

    suspend fun marquerSansVo(identite: String) {
        if (identite.isBlank()) return
        store.edit { prefs ->
            val actuelles = prefs[SANS_VO]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            if (identite in actuelles) return@edit
            // Bornée : la liste ne fait que grandir, et les plus anciennes
            // désignent des torrents que plus personne ne propose.
            prefs[SANS_VO] = (actuelles + identite).takeLast(PLAFOND).joinToString(",")
        }
    }

    private companion object {
        const val STORE = "moovie_source_audio"
        val SANS_VO = stringPreferencesKey("sans_vo")
        const val PLAFOND = 300
    }
}
