package fr.moovie.tv.data.sources

import fr.moovie.tv.core.sources.model.EmbedLink
import fr.moovie.tv.core.sources.model.PlayableStream
import fr.moovie.tv.core.sources.model.StreamFormat
import fr.moovie.tv.core.sources.port.HttpGateway
import fr.moovie.tv.core.sources.port.HttpMethod
import fr.moovie.tv.core.sources.port.HttpRequest
import fr.moovie.tv.core.sources.port.SourceExtractor
import fr.moovie.tv.shared.Chrono
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Résout les liens de [StremioProvider], qui sont **presque** déjà des fichiers.
 *
 * Un lien d'addon n'est pas l'URL du média mais celle d'un **aiguillage** chez
 * l'addon : `GET` dessus, et Torrentio répond `302` vers l'adresse du fichier
 * chez le debrid. Là où les sept autres catalogues rendent une page
 * d'hébergeur à ouvrir et désobfusquer, il n'y a ici qu'une redirection à
 * suivre — et c'est ce que fait cet extracteur, pour deux raisons :
 *
 *  - **donner au lecteur l'adresse finale** : le suivre ici épargne au lecteur
 *    un aller-retour, et la sonde de jouabilité travaille sur le vrai fichier ;
 *  - **savoir si le fichier est là**. Lu dans le code de Torrentio
 *    (`addon/moch/realdebrid.js`, `static.js`) : quand le torrent n'est pas
 *    encore chez le debrid, l'aiguillage l'y **ajoute** et redirige vers une
 *    vidéo statique (`videos/downloading_v3.mp4`) le temps du téléchargement ;
 *    en cas d'échec, vers une autre (`download_failed_v3.mp4`,
 *    `failed_opening_v3.mp4`, `failed_rar_v3.mp4`, `limits_exceeded_v2.mp4`…).
 *    Ce sont des MP4 valides de quelques secondes : laissés au lecteur, ils
 *    « jouent », et l'utilisateur voit un carton à la place de son épisode.
 *
 * Pour un lien marqué [EmbedLink.deferred], « en cours » n'est pas un échec :
 * on interroge l'aiguillage toutes les [INTERVALLE_MS] jusqu'à
 * [DELAI_RECUPERATION], le temps que Real-Debrid aille chercher le fichier.
 * Sam, 10/10/2026 : « soit il faut que j'aie un moyen de choisir une autre
 * version dans Torrentio, soit il faut proposer que des sources où il y a une
 * vraie piste espagnole ».
 *
 * ## Pourquoi il ne revendique aucun domaine
 *
 * [canHandle] rend toujours false. La résolution l'atteint par son **nom** —
 * première règle de `StreamResolution`, qui route sur `EmbedLink.hoster` — et
 * c'est la seule voie sûre ici : l'URL d'un flux Stremio dépend de l'addon et du
 * debrid de chaque utilisateur, et prétendre reconnaître cette famille à son
 * domaine reviendrait à tenir une liste qui sera fausse chez le prochain.
 *
 * L'effet de bord évité est concret : `DirectStreamExtractor` revendique tout
 * `.mp4`, donc sans le routage par nom un fichier `.mp4` servi par un debrid lui
 * reviendrait, avec un `Referer` qui n'a pas lieu d'être.
 *
 * ## Le format
 *
 * `UNKNOWN` pour tout ce qui n'est pas reconnu à son extension, et en
 * particulier pour le `.mkv` qui domine ce catalogue. Ce n'est pas un aveu
 * d'ignorance mais le seul choix juste : l'énumération n'a pas d'entrée
 * Matroska, et le ranger sous `MP4` le ferait annoncer en `video/mp4` au
 * récepteur Chromecast — un type faux, refusé à l'arrivée. `UNKNOWN` laisse
 * chaque lecteur reconnaître le conteneur, ce que Media3 fait sur le contenu.
 *
 * @param http la passerelle, null pour l'ancien comportement (le lien tel quel,
 *   sans suivre l'aiguillage) — les tests d'analyse n'en ont pas besoin.
 * @param intervalleMs cadence des réinterrogations pendant un téléchargement
 *   chez le debrid ; raccourcie par les tests.
 */
class StremioExtractor(
    private val http: HttpGateway? = null,
    private val intervalleMs: Long = INTERVALLE_MS,
) : SourceExtractor {

    override val hoster = HOSTER

    override fun canHandle(url: String) = false

    override suspend fun extract(link: EmbedLink): PlayableStream? {
        if (link.url.isBlank()) return null
        val url = when (val issue = http?.let { aiguillage(it, link) } ?: Issue.Inconnue) {
            is Issue.Prete -> issue.url
            Issue.Morte -> return null
            Issue.Inconnue -> link.url
        }
        return PlayableStream(
            url = url,
            format = formatOf(url),
            // Pas de `Referer` ni d'`Origin` : ces CDN n'en demandent pas, et en
            // inventer un est le genre de détail qui fait répondre 403 sans
            // qu'on sache pourquoi. Seul l'agent est envoyé, comme partout.
            headers = mapOf("User-Agent" to Ua.BROWSER),
            language = link.language,
            quality = link.variant,
        )
    }

    private sealed interface Issue {
        data class Prete(val url: String) : Issue
        data object Morte : Issue
        /** Pas pu interroger l'aiguillage : le lecteur suivra lui-même. */
        data object Inconnue : Issue
    }

    /**
     * Suit l'aiguillage de l'addon, en attendant le debrid s'il le faut.
     *
     * Une réponse sans redirection ni erreur (l'addon a changé de forme) rend
     * [Issue.Inconnue] : on redonne le lien tel quel plutôt que de déclarer
     * mort ce qu'on ne comprend pas.
     */
    private suspend fun aiguillage(http: HttpGateway, link: EmbedLink): Issue {
        val debut = TimeSource.Monotonic.markNow()
        var annonce = false
        while (true) {
            val reponse = http.fetch(
                HttpRequest(
                    url = link.url,
                    method = HttpMethod.GET,
                    headers = mapOf("User-Agent" to Ua.BROWSER, "Accept" to "*/*"),
                    followRedirects = false,
                ),
            ) ?: return Issue.Inconnue
            if (reponse.status == 404) return Issue.Morte
            val cible = reponse.headers.entries
                .firstOrNull { it.key.equals("location", ignoreCase = true) }
                ?.value?.takeIf { it.isNotBlank() }
                ?: return Issue.Inconnue
            when {
                !isStaticVideo(cible) -> return Issue.Prete(cible)
                !cible.endsWith(DOWNLOADING) -> {
                    Chrono.repere("stremio : le debrid refuse (${cible.substringAfterLast('/')})")
                    return Issue.Morte
                }
                debut.elapsedNow() >= DELAI_RECUPERATION -> {
                    Chrono.repere("stremio : le debrid n'a pas fini en ${DELAI_RECUPERATION.inWholeMinutes} min, on abandonne")
                    return Issue.Morte
                }
            }
            if (!annonce) {
                annonce = true
                Chrono.repere("stremio : Real-Debrid récupère le fichier (${link.variant.orEmpty()})")
            }
            delay(intervalleMs)
        }
    }

    companion object {

        /** Doit rester égal au `hoster` émis par [StremioProvider] : c'est la jointure. */
        const val HOSTER = "stremio"

        /** Cadence d'interrogation pendant que le debrid télécharge. */
        const val INTERVALLE_MS = 5_000L

        /**
         * Au-delà, on abandonne : un torrent bien partagé arrive chez
         * Real-Debrid en une ou deux minutes, un torrent mort jamais.
         */
        val DELAI_RECUPERATION = 4.minutes

        /** La vidéo statique « téléchargement en cours » de Torrentio. */
        private const val DOWNLOADING = "downloading_v3.mp4"

        /**
         * Les cartons de Torrentio : `videos/<etat>_v<n>.mp4` sur l'hôte de
         * l'addon. Reconnus à leur forme, pour ne pas dépendre de la liste du
         * jour (elle en compte onze, et la version change avec le dessin).
         */
        private val STATIQUE = Regex("""/videos/[a-z_]+_v\d+\.mp4$""")

        fun isStaticVideo(url: String): Boolean = STATIQUE.containsMatchIn(url.substringBefore('?'))

        private fun formatOf(url: String): StreamFormat {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".m3u8") -> StreamFormat.HLS
                path.endsWith(".mpd") -> StreamFormat.DASH
                path.endsWith(".mp4") || path.endsWith(".m4v") -> StreamFormat.MP4
                else -> StreamFormat.UNKNOWN
            }
        }
    }
}
