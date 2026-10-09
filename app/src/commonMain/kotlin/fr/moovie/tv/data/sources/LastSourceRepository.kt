package fr.moovie.tv.data.sources

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.moovie.tv.core.sources.model.EmbedLink
import fr.moovie.tv.data.store.preferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * La source qui a joué la dernière fois, par titre (clé de lecture).
 *
 * Sam, 09/10/2026 : « à chaque fois que je reprends la vidéo, ça me dit
 * "recherche d'une source vidéo sur Stremio", alors qu'on vient de le lire il y
 * a 5 minutes ». Et ce n'est pas qu'une attente : une recherche qui repart de
 * zéro peut retenir un **autre fichier**, avec d'autres pistes — un épisode vu
 * en japonais est revenu en anglais. Reprendre, c'est reprendre le même.
 *
 * Ce qu'on garde est le lien d'embed, stable, et non le flux résolu, qui
 * expire : la reprise le re-résout. Un lien qui ne répond plus est oublié, et
 * la cascade reprend la main.
 *
 * Magasin à part, hors sauvegarde et hors synchronisation : l'URL d'un lien
 * Stremio porte la clé du debrid dans son chemin.
 */
class LastSourceRepository {
    private val store = preferencesStore(STORE)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(key: String): EmbedLink? =
        store.data.first()[stringPreferencesKey(key)]
            ?.let { raw -> runCatching { json.decodeFromString<EmbedLink>(raw) }.getOrNull() }

    suspend fun remember(key: String, link: EmbedLink) {
        if (key.isBlank()) return
        store.edit { it[stringPreferencesKey(key)] = json.encodeToString(link) }
    }

    suspend fun forget(key: String) {
        store.edit { it.remove(stringPreferencesKey(key)) }
    }

    private companion object {
        const val STORE = "moovie_last_source"
    }
}
