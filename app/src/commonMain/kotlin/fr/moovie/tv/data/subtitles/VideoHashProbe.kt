package fr.moovie.tv.data.subtitles

import fr.moovie.tv.core.sources.model.PlayableStream
import fr.moovie.tv.core.sources.model.StreamFormat
import fr.moovie.tv.core.sources.port.HttpGateway
import fr.moovie.tv.core.sources.port.HttpMethod
import fr.moovie.tv.core.sources.port.HttpRequest
import fr.moovie.tv.core.subtitles.model.VideoIdentity
import fr.moovie.tv.core.subtitles.usecase.HASH_CHUNK
import fr.moovie.tv.core.subtitles.usecase.openSubtitlesHash
import fr.moovie.tv.shared.Chrono
import fr.moovie.tv.shared.systemeFichiers
import okio.Path.Companion.toPath

/**
 * L'empreinte OpenSubtitles du flux joué, quand le flux est un **fichier**.
 *
 * Deux requêtes par plage sur l'adresse du média — les 64 premiers kilo-octets,
 * dont la réponse `Content-Range` donne la taille, puis les 64 derniers — et
 * le calcul de `openSubtitlesHash`. Les CDN de debrid répondent 206 à une
 * plage (mesuré sur Real-Debrid par la sonde de jouabilité) ; un serveur qui
 * répond 200 au lieu de 206 ignore les plages, et son corps n'est pas la tête
 * du fichier : on renonce plutôt que de calculer faux.
 *
 * Un flux segmenté (HLS, DASH) n'a pas de fichier : null, comme avant.
 * Un fichier local (`file://`) se lit directement.
 */
suspend fun videoIdentity(http: HttpGateway, stream: PlayableStream): VideoIdentity? {
    if (stream.format == StreamFormat.HLS || stream.format == StreamFormat.DASH) return null
    val chemin = stream.url.substringBefore('?').substringBefore('#').lowercase()
    if (chemin.endsWith(".m3u8") || chemin.endsWith(".mpd")) return null
    val chrono = Chrono.debut("sous-titres : empreinte du fichier")
    val identite = runCatching {
        if (stream.url.startsWith("file:")) empreinteLocale(stream.url) else empreinteDistante(http, stream)
    }.getOrNull()
    chrono.fin(identite?.let { "${it.hash} (${it.sizeBytes} octets)" } ?: "indisponible")
    return identite
}

private suspend fun empreinteDistante(http: HttpGateway, stream: PlayableStream): VideoIdentity? {
    suspend fun plage(valeur: String) = http.fetch(
        HttpRequest(
            url = stream.url,
            method = HttpMethod.GET,
            headers = stream.headers + ("Range" to valeur),
            binary = true,
        ),
    )
    val tete = plage("bytes=0-${HASH_CHUNK - 1}") ?: return null
    if (tete.status != 206) return null
    val taille = tete.headers.entries
        .firstOrNull { it.key.equals("content-range", ignoreCase = true) }
        ?.value?.substringAfterLast('/')?.trim()?.toLongOrNull()
        ?: return null
    val debut = tete.bytes?.takeIf { it.size >= HASH_CHUNK }?.copyOf(HASH_CHUNK) ?: return null
    val queue = plage("bytes=-$HASH_CHUNK")
        ?.takeIf { it.status == 206 }?.bytes?.takeIf { it.size >= HASH_CHUNK }
        ?.let { it.copyOfRange(it.size - HASH_CHUNK, it.size) }
        ?: return null
    val hash = openSubtitlesHash(taille, debut, queue) ?: return null
    return VideoIdentity(hash, taille)
}

private fun empreinteLocale(url: String): VideoIdentity? {
    val chemin = url.removePrefix("file://").removePrefix("file:").toPath()
    val taille = systemeFichiers.metadataOrNull(chemin)?.size ?: return null
    if (taille < 2L * HASH_CHUNK) return null
    val debut = ByteArray(HASH_CHUNK)
    val queue = ByteArray(HASH_CHUNK)
    systemeFichiers.openReadOnly(chemin).use { fichier ->
        fichier.read(0L, debut, 0, HASH_CHUNK)
        fichier.read(taille - HASH_CHUNK, queue, 0, HASH_CHUNK)
    }
    val hash = openSubtitlesHash(taille, debut, queue) ?: return null
    return VideoIdentity(hash, taille)
}
