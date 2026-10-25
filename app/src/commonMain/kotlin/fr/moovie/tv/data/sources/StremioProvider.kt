package fr.moovie.tv.data.sources

import fr.moovie.tv.core.sources.model.EmbedLink
import fr.moovie.tv.core.sources.model.MediaRef
import fr.moovie.tv.core.sources.port.HttpGateway
import fr.moovie.tv.core.sources.port.SourceProvider
import fr.moovie.tv.core.sources.port.getBody
import fr.moovie.tv.data.settings.SettingsRepository
import fr.moovie.tv.data.tmdb.TmdbRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provider stremio — les flux de **l'addon de l'utilisateur** (Torrentio, Comet…),
 * servis par son debrid.
 *
 * Seul catalogue de cette application qui n'a aucun site à scraper : le protocole
 * des addons Stremio est public et rend du JSON. `GET {addon}/stream/{type}/{id}.json`,
 * et `streams[]` arrive. Rien à désobfusquer, rien qui dérive au prochain
 * changement de thème WordPress.
 *
 * Ce qui le distingue vraiment des sept autres : les liens sont des **fichiers**
 * (mkv, mp4) sur le CDN du debrid, pas des pages d'hébergeur. Pas de cascade
 * d'extraction, pas de `Referer` à deviner — voir [StremioExtractor].
 *
 * ## Le pont d'identifiants
 *
 * Stremio n'indexe que l'IMDB, cette application que le TMDB. La traduction
 * passe par `/external_ids` et se mémorise ; c'est le seul appel réseau
 * supplémentaire, et il est mutualisé avec le reste de l'app.
 *
 * Un titre que TMDB ne relie à aucun IMDB rend une liste vide. Ce n'est pas une
 * panne : il n'y a simplement rien à demander à l'addon.
 *
 * L'URL de l'addon est **codée en dur** : ce fork est personnel, et un écran de
 * réglages ne se justifiera que le jour où on saura que la lecture tient sur le
 * téléviseur. Voir [StremioConfig], qui dit aussi pourquoi git l'ignore.
 *
 * ## Pourquoi les flux non mis en cache sont écartés
 *
 * Un flux marqué `[RD+]` (ou `[AD+]`, `[PM+]`, `[TB+]`) est **déjà sur les
 * serveurs du debrid** : son URL joue tout de suite. Sans ce marqueur, le lien
 * ne fait que *demander* au debrid d'aller chercher le torrent — il répond au
 * bout de plusieurs minutes, ou jamais si plus personne ne partage.
 *
 * Or la promesse de cette application est un seul appui sur **Lire**. Servir un
 * lien qui met dix minutes à exister, c'est un bouton qui ne fait rien, et
 * l'utilisateur n'a aucun moyen de savoir lequel des deux cas il vient de
 * toucher. On ne rapporte donc que ce qui est jouable maintenant.
 *
 * Mesuré par `stremio-mpv` sur 77 flux d'un même titre : Torrentio **ne déclare
 * ni taille ni seeders en champ structuré** (`behaviorHints.videoSize` absent
 * des 77). Tout est dans le texte de `title`, d'où l'analyse par motif.
 */
class StremioProvider(
    private val http: HttpGateway,
    /**
     * N'accepter que les flux déjà présents chez le debrid. À passer à false
     * pour un usage où l'attente est acceptable — jamais sur une télévision.
     */
    private val cachedOnly: Boolean = true,
    /**
     * Manifeste de l'addon imposé, pour les tests et les sondes. Null en
     * fonctionnement normal : il se déduit du debrid choisi dans les réglages
     * et de sa clé dans [StremioConfig].
     */
    private val addonUrl: String? = null,
    /**
     * Clé TMDB imposée, au lieu de celle des réglages. Couture pour les sondes,
     * qui tournent sans magasin de réglages ; null en fonctionnement normal.
     */
    private val cleTmdb: String? = null,
    /**
     * Dépendances **différées**, et pas par goût de l'indirection : `all` de
     * `ProviderRegistry` se construit au premier accès, y compris depuis l'écran
     * des réglages qui ne veut que la liste des noms. Ouvrir un DataStore à ce
     * moment-là serait un effet de bord invisible et hors sujet. Elles ne se
     * construisent donc qu'à la première recherche de sources — et jamais dans
     * les tests d'analyse, qui n'appellent que [parse].
     */
    fabriqueReglages: () -> SettingsRepository = { SettingsRepository() },
    fabriqueTmdb: () -> TmdbRepository = { TmdbRepository() },
) : SourceProvider {

    override val name = "stremio"

    private val settings by lazy(fabriqueReglages)
    private val tmdb by lazy(fabriqueTmdb)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun sourcesFor(media: MediaRef): List<EmbedLink> {
        val bases = (addonUrl?.let { listOf(it) } ?: basesParPriorite())
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
        if (bases.isEmpty()) return emptyList()

        val apiKey = cleTmdb ?: settings.tmdbApiKey.first()
        val isTv = media is MediaRef.Episode
        val imdb = tmdb.imdbId(apiKey, isTv, media.tmdbId) ?: return emptyList()

        val (type, id) = when (media) {
            is MediaRef.Movie -> "movie" to imdb
            is MediaRef.Episode -> "series" to "$imdb:${media.season}:${media.episode}"
        }

        // Le `User-Agent` n'est pas décoratif : mesuré, Torrentio répond **403**
        // à une requête qui n'en porte pas d'ordinaire (`Python-urllib` refusé,
        // `stremio-mpv/1.0` accepté). OkHttp enverrait `okhttp/4.x`, qui tombe
        // dans la même catégorie. La panne aurait été muette — un catalogue qui
        // rend une liste vide sur chaque fiche, sans erreur nulle part.
        //
        // Tous les debrids configurés sont interrogés **ensemble**, chacun sous
        // son propre délai : le 25/10/2026, TorBox seul a rendu 0 flux sur
        // Fullmetal Alchemist (Torrentio : « TorBox is currently unavailable »)
        // pendant que Real-Debrid en rendait 75. Un seul service, c'est une
        // fiche vide à chacune de ses pannes. Voir [fusion].
        val corps = coroutineScope {
            bases.map { base ->
                async {
                    withTimeoutOrNull(DEBRID_TIMEOUT_MS) {
                        http.getBody(
                            "$base/stream/$type/$id.json",
                            headers = mapOf("User-Agent" to Ua.BROWSER, "Accept" to "application/json"),
                        )
                    }
                }
            }.awaitAll()
        }
        val utiles = corps.filterNotNull().filter(::reponseUtile)
        // Aucun debrid n'a répondu pour de bon : c'est une panne, pas un titre
        // sans flux. Une liste vide serait mise en cache six heures comme une
        // réponse ; l'exception marque le catalogue en échec, et la prochaine
        // ouverture de la fiche redemande.
        if (utiles.isEmpty()) throw DebridIndisponible()

        val cible = if (media is MediaRef.Episode) CIBLE_EPISODE else CIBLE_FILM
        return fusion(utiles.map { classer(it, cible, media.originalLanguage) })
    }

    /**
     * Les adresses d'addon de chaque debrid qui a une clé, celui des réglages
     * en tête : il départage deux fichiers identiques, voir [fusion].
     */
    private suspend fun basesParPriorite(): List<String> {
        val prefere = settings.debridService.first()
        return StremioConfig.disponibles()
            .sortedBy { if (it == prefere) 0 else 1 }
            .map { StremioConfig.addonUrl(it) }
    }

    /**
     * Sans réseau, donc testable sur une réponse enregistrée — c'est là que vit
     * tout ce qui peut se tromper.
     */
    internal fun parse(body: String, cible: Long = CIBLE_FILM, originalLanguage: String = ""): List<EmbedLink> =
        classer(body, cible, originalLanguage).map { it.lien }

    /**
     * Fusionne les listes de plusieurs debrids, chacune déjà classée, la
     * première étant celle du debrid préféré.
     *
     * On ne dédoublonne **pas** entre services : un même fichier chez
     * Real-Debrid et chez TorBox a deux URL, et c'est précisément ce qui sert.
     * À rang égal (définition, codec, poids identiques), le tri stable les
     * place côte à côte, le préféré d'abord — si sa lecture échoue (panne,
     * « failed_infringement » de Real-Debrid), la cascade du lecteur essaie le
     * même fichier chez l'autre avant de passer à une autre release.
     *
     * Le nom du debrid s'ajoute à la variante dès que deux services ont
     * répondu : sans lui, le panneau des sources montrerait des doublons
     * apparents.
     */
    internal fun fusion(listes: List<List<Classe>>): List<EmbedLink> {
        if (listes.size == 1) return listes.single().map { it.lien }
        return listes
            .flatMapIndexed { rang, liste -> liste.map { rang to it } }
            .sortedWith(
                compareBy(
                    { it.second.lien.deferred },
                    { it.second.definition },
                    { it.second.codec },
                    { ecart(it.second.octets, it.second.cible) },
                    { it.first },
                ),
            )
            .map { (_, c) ->
                val service = debridDe(c.lien.url) ?: return@map c.lien
                c.lien.copy(variant = listOfNotNull(c.lien.variant, service.nom).joinToString(" · "))
            }
    }

    internal fun classer(body: String, cible: Long, originalLanguage: String): List<Classe> {
        val streams = runCatching {
            json.parseToJsonElement(body).jsonObject["streams"]?.jsonArray
        }.getOrNull() ?: return emptyList()

        val seen = mutableSetOf<String>()
        return streams.mapNotNull { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            // Pas d'`url` = flux magnet pur, à jouer par un moteur torrent que
            // cette application n'a pas. L'écarter ici plutôt que de le laisser
            // échouer dans le lecteur.
            val url = o["url"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null

            val name = o["name"]?.jsonPrimitive?.contentOrNull().orEmpty()
            val title = o["title"]?.jsonPrimitive?.contentOrNull().orEmpty()
            val cached = CACHED_RE.containsMatchIn(name)
            if (cachedOnly && !cached) return@mapNotNull null

            // Dédoublonnage sur l'**URL exacte**, jamais sur l'infohash : mesuré
            // sur 77 flux, 75 infohash distincts mais 77 URL — deux entrées
            // partagent un torrent et diffèrent par le fichier visé, le film
            // d'un côté et un bonus de l'autre. Ce ne sont pas des doublons.
            if (!seen.add(url)) return@mapNotNull null

            val filename = o["behaviorHints"]?.jsonObject
                ?.get("filename")?.jsonPrimitive?.contentOrNull()
                ?: title.lineSequence().firstOrNull().orEmpty()

            val release = "$filename\n$title"
            if (undecodable(release)) return@mapNotNull null
            // Un doublage dans une langue que personne n'a demandée n'est ni
            // une VO ni une VF : il n'a rien à faire sur la fiche.
            val annoncee = langueAnnoncee(release)
            if (doublageEtranger(annoncee, release, originalLanguage)) return@mapNotNull null

            Classe(
                lien = EmbedLink(
                    url = url,
                    hoster = StremioExtractor.HOSTER,
                    language = languageOf(filename),
                    variant = variantOf(name, title, cached, annoncee?.second),
                    // Pas chez le debrid : le lire lui demande d'abord d'aller
                    // le chercher. Voir StremioExtractor.
                    deferred = !cached,
                ),
                definition = qualityRank(qualityOf(name)),
                codec = codecRank(release),
                octets = sizeBytes(title),
                cible = cible,
            )
        }
            // Ce qui joue tout de suite d'abord ; parmi ceux-là, la définition
            // que la télé lit, le codec qu'elle avoue, le poids visé.
            .sortedWith(
                compareBy({ it.lien.deferred }, { it.definition }, { it.codec }, { ecart(it.octets, cible) }),
            )
    }

    /** Un lien et les nombres qui décident de sa place. Interne au tri. */
    internal data class Classe(
        val lien: EmbedLink,
        val definition: Int,
        val codec: Int,
        val octets: Long?,
        val cible: Long,
    )

    /** Aucun debrid n'a rendu de flux exploitable : panne, à redemander. */
    class DebridIndisponible : Exception("aucun debrid n'a répondu")

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        content.takeIf { it.isNotBlank() && it != "null" }

    internal companion object {

        /**
         * Délai par debrid, sous les 12 s que l'écran des détails accorde au
         * catalogue entier : un service qui traîne (TorBox le 25/10/2026, 5 s
         * par appel quand il répondait) est abandonné à temps pour que l'autre
         * soit servi.
         */
        const val DEBRID_TIMEOUT_MS = 9_000L

        /**
         * Vrai si l'addon a réellement répondu pour ce debrid : au moins un flux
         * avec une URL qui ne soit pas un carton d'erreur. Torrentio avec un
         * debrid rend tous les torrents connus, en cache ou non ; une liste vide
         * là où l'addon sans debrid en voit 57 (Fullmetal Alchemist, 25/10/2026),
         * ou le seul carton « TB error », c'est le debrid qui est tombé.
         */
        fun reponseUtile(body: String): Boolean {
            val streams = runCatching {
                Json.parseToJsonElement(body).jsonObject["streams"]?.jsonArray
            }.getOrNull() ?: return false
            return streams.any { element ->
                val o = runCatching { element.jsonObject }.getOrNull() ?: return@any false
                val url = o["url"]?.jsonPrimitive?.content
                val name = o["name"]?.jsonPrimitive?.content.orEmpty()
                !url.isNullOrBlank() && url != "null" && !ERREUR_RE.containsMatchIn(name)
            }
        }

        /** `Torrentio\nTB error`, `RD error` : le carton qu'affiche Torrentio quand son debrid échoue. */
        private val ERREUR_RE = Regex("""\b[A-Z]{2} error\b""")

        /** Le debrid d'une URL de résolution Torrentio (`/resolve/torbox/…`), ou null. */
        fun debridDe(url: String): DebridService? =
            DebridService.entries.firstOrNull { url.contains("/resolve/${it.parametre}/") }

        /**
         * Vrai pour un encodage qu'aucun décodeur matériel courant ne lit.
         *
         * Mesuré le 09/10/2026 sur la TCL (puce Realtek, Android 9) : un
         * Fullmetal Alchemist en `avc1.6E0832` — H.264 **High 10**, le « Hi10P »
         * des fansubs d'anime — est déclaré `NO_EXCEEDS` par ExoPlayer et fait
         * planter `OMX.realtek.video.decoder`. Le lecteur rend alors la main à
         * la fiche, sans rien jouer. Le décodeur logiciel ne sauve rien : il
         * refuse ce profil lui aussi, et un ARM 32 bits n'y suffirait pas.
         *
         * Écartés : le Hi10P sous ses noms de release (`Hi10`, `Hi10P`), le
         * H.264 dit 10 bits, et l'AV1 — la TV ne déclare aucun décodeur AV1.
         * Le **HEVC 10 bits reste** : Main10 est le profil du HDR10, que la
         * puce décode.
         */
        fun undecodable(text: String): Boolean {
            val t = text.uppercase()
            val hevcAnnonce = Regex("""\b(X265|HEVC|H[ .]?265)\b""").containsMatchIn(t)
            if (Regex("""\bHI10P?\b""").containsMatchIn(t)) return true
            // « Ma10 » : le 10 bits H.264 de certains groupes, sans le mot Hi10.
            if (!hevcAnnonce && Regex("""\bMA10\b""").containsMatchIn(t)) return true
            if (Regex("""\bAV1\b""").containsMatchIn(t)) return true
            val tenBit = Regex("""\b10[ ._-]?BITS?\b""").containsMatchIn(t)
            val avc = Regex("""\b(X264|AVC|H[ .]?264)\b""").containsMatchIn(t)
            val hevc = Regex("""\b(X265|HEVC|H[ .]?265)\b""").containsMatchIn(t)
            return tenBit && avc && !hevc
        }

        /**
         * Ce qu'on sait du codec vidéo, du plus sûr au moins sûr : 0 pour le
         * HEVC annoncé, 1 pour un H.264 annoncé comme tel (8 bits, donc
         * décodable), 2 quand le nom ne dit rien.
         *
         * Le filtre [undecodable] ne voit que ce que le nom avoue. Mesuré sur
         * Fullmetal Alchemist le 09/10/2026 : trois rips Blu-ray d'affilée
         * (« BD 1080p », sans autre mention) étaient du Hi10P, choisis parce
         * que leur poids collait au poids visé ; la cascade les a essayés l'un
         * après l'autre, trois échecs de décodage. Placer d'abord ce qui
         * s'annonce lisible évite de jouer à la loterie.
         */
        fun codecRank(text: String): Int {
            val t = text.uppercase()
            return when {
                Regex("""\b(X265|HEVC|H[ .]?265)\b""").containsMatchIn(t) -> 0
                Regex("""\b(X264|AVC|H[ .]?264|WEB[ .-]?DL|WEBRIP)\b""").containsMatchIn(t) -> 1
                else -> 2
            }
        }

        /** `[RD+]`, `[AD+]`, `[PM+]`, `[TB+]`, `[DL+]` — le `+` dit « déjà chez eux ». */
        val CACHED_RE = Regex("""\[[A-Z]{2}\+]""")

        /** `💾 1.78 GB` — le poids, lu dans le texte de `title` faute de champ. */
        val SIZE_RE = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|MB)""", RegexOption.IGNORE_CASE)

        /** Deuxième ligne de `name` chez Torrentio : `1080p`, `4k`, `720p`… */
        fun qualityOf(name: String): String? =
            name.lineSequence().drop(1).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

        /** Facteurs des unités que Torrentio écrit dans son texte. */
        val UNITES = mapOf("MB" to 1_000_000L, "GB" to 1_000_000_000L, "TB" to 1_000_000_000_000L)

        /**
         * Poids en octets, ou null si le texte n'en porte pas.
         *
         * Sert au tri, pas à l'affichage : il faut un nombre pour comparer, et
         * la chaîne « 3.41 GB » n'en est pas un.
         */
        fun sizeBytes(title: String): Long? {
            val m = SIZE_RE.find(title) ?: return null
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val u = UNITES[m.groupValues[2].uppercase()] ?: return null
            return (n * u).toLong()
        }

        /**
         * Poids visé, par type de média. Ni un plancher ni un plafond : le point
         * dont on s'éloigne le moins.
         *
         * Trier simplement du plus léger au plus lourd paraissait prudent — il
         * mettait en tête, mesuré sur *Dune*, un fichier de **500 Mo** pour deux
         * heures et demie, soit un débit qui se voit à l'écran. Trier à
         * l'inverse mettait un REMUX de 40 Go que le téléviseur ne décode pas.
         * Les deux extrêmes sont mauvais, et c'est ce qui disqualifie un tri par
         * poids : ce qu'on cherche est une plage, pas un bout de liste.
         *
         * 4 Go pour un film de deux heures, 1,5 Go pour un épisode de
         * quarante-cinq minutes : à peu près le même débit, environ 4 Mb/s, qui
         * est le palier où un encodage 1080p cesse de montrer ses défauts sans
         * demander plus que ce qu'une connexion domestique sert en continu.
         */
        const val CIBLE_FILM = 4_000_000_000L
        const val CIBLE_EPISODE = 1_500_000_000L

        /**
         * Écart au poids visé. Un flux dont l'addon ne déclare pas le poids
         * passe en dernier de son rang plutôt que d'être écarté : il est
         * peut-être parfait, mais rien ne permet de le dire, et le mettre devant
         * un flux mesuré serait un pari fait à la place de l'utilisateur.
         */
        fun ecart(octets: Long?, cible: Long): Long =
            if (octets == null) Long.MAX_VALUE else kotlin.math.abs(octets - cible)

        /**
         * Rang de définition, du plus jouable au moins jouable **sur la télé
         * visée** — et non de la meilleure image à la moins bonne.
         *
         * L'ordre 1080p, 720p, puis 4K n'est pas une erreur de frappe. Le
         * téléviseur est un TCL sous Android 9 en ARM 32 bits : un remux 2160p
         * HEVC 10 bits avec piste TrueHD n'y sera pas décodé, et il pèse de
         * toute façon plus que ce qu'une connexion domestique sait servir en
         * temps réel. Mesuré sur *Dune* : le premier flux en cache que l'addon
         * propose est un REMUX de **39,9 Go** en DTS-HD MA / TrueHD Atmos.
         *
         * Sans ce classement, le bouton **Lire** — qui joue le premier lien de
         * la langue voulue — tomberait dessus à chaque fois, et le catalogue
         * passerait pour cassé alors qu'il a rendu 181 flux dont 75 jouables.
         * Les autres restent dans le panneau des sources, à un appui près.
         */
        fun qualityRank(quality: String?): Int = when {
            quality == null -> 3
            quality.contains("1080", ignoreCase = true) -> 0
            quality.contains("720", ignoreCase = true) -> 1
            quality.contains("2160", ignoreCase = true) || quality.contains("4k", ignoreCase = true) -> 4
            quality.contains("480", ignoreCase = true) -> 2
            else -> 3
        }

        /**
         * Langue déduite du **nom de fichier**, seul endroit où la release la
         * déclare — l'addon, lui, n'a pas de champ pour ça.
         *
         * `MULTi` compte comme VF : la convention des releases francophones est
         * qu'il porte au moins une piste française en plus de l'originale.
         * Faute de marqueur, on conclut VO et non VF : les catalogues Stremio
         * sont anglophones par défaut, et se tromper vers la VO fait manquer un
         * doublage, alors que se tromper vers la VF met un film anglais dans la
         * section française — l'erreur qui se voit au moment de lire.
         */
        fun languageOf(filename: String): String {
            val f = filename.uppercase()
            return when {
                Regex("""\bVOSTFR\b""").containsMatchIn(f) -> "VOSTFR"
                Regex("""\b(TRUEFRENCH|FRENCH|VFF|VFQ|VF2?|MULTI)\b""").containsMatchIn(f) -> "VF"
                else -> "VO"
            }
        }

        /**
         * Ce que le fichier porte comme audio, pour choisir sur la fiche :
         * « multi-audio » quand l'original et un doublage cohabitent (le
         * lecteur prend alors la langue voulue), « doublé » quand le fichier
         * n'a qu'un doublage — étiqueté VO faute de marqueur français, il ne
         * contient pourtant pas la version originale.
         *
         * Torrentio l'écrit dans `title` (`Dubbed / Dual Audio`), les releases
         * dans le nom (`[Dual-Audio]`, `Multi-Audio`, `Tri Audio`).
         */
        fun audioOf(title: String): String? {
            val t = title.uppercase()
            return when {
                MULTI_AUDIO_RE.containsMatchIn(t) -> "multi-audio"
                Regex("""\bDUBBED\b""").containsMatchIn(t) -> "doublé"
                else -> null
            }
        }

        private val MULTI_AUDIO_RE = Regex("""\b(DUAL|MULTI|TRI)[ ._-]?AUDIO\b""")

        /**
         * Ce qui sépare deux liens du même debrid dans la même langue : la
         * définition et le poids. Sur ce catalogue il y en a des dizaines par
         * titre, c'est donc le seul critère de choix qui reste à l'écran.
         *
         * Le poids n'est pas décoratif : c'est lui qui dit qu'un remux de 60 Go
         * ne passera pas sur une connexion domestique, là où la définition seule
         * laisserait croire que tous les « 1080p » se valent.
         */
        fun variantOf(name: String, title: String, cached: Boolean, langue: String? = null): String? {
            val parts = buildList {
                qualityOf(name)?.let { add(it) }
                SIZE_RE.find(title)?.let { add(it.value.replace(',', '.')) }
                audioOf(title)?.let { add(it) }
                // La langue que la release annonce (« espagnol »), quand elle
                // le fait : sur une fiche VO, c'est ce qui distingue la vraie
                // version originale d'un « MultiLang » qui ne la porte pas.
                langue?.let { add(it) }
                // Pas encore chez le debrid : Real-Debrid ira le chercher au
                // moment de lire, une à deux minutes pour un torrent partagé.
                if (!cached) add("à récupérer")
            }
            return parts.joinToString(" · ").takeIf { it.isNotBlank() }
        }

        /**
         * La langue audio que la release **annonce**, code ISO 639-1 et nom en
         * français, ou null quand le nom ne dit rien. Les marqueurs de
         * sous-titres (`EngSub`, `SweSub`) ne comptent pas : ils ne forment pas
         * un mot seul. Un titre en cyrillique vient d'un tracker russe et porte
         * un doublage russe, « Original + » ou pas.
         */
        fun langueAnnoncee(release: String): Pair<String, String>? {
            val r = release.uppercase()
            LANGUES_RELEASE.firstOrNull { (motif, _) -> motif.containsMatchIn(r) }?.let { return it.second }
            if (CYRILLIQUE.containsMatchIn(release)) return "ru" to "russe"
            return null
        }

        /**
         * Vrai pour un doublage dans une autre langue que l'originale — et
         * seulement quand on connaît l'originale et que la release ne dit pas
         * porter plusieurs pistes. Le français n'arrive jamais ici : `FRENCH`
         * et consorts font une VF, voir [languageOf].
         */
        fun doublageEtranger(annoncee: Pair<String, String>?, release: String, originalLanguage: String): Boolean {
            val original = originalLanguage.trim().lowercase()
            if (original.isEmpty() || original == "xx") return false
            val code = annoncee?.first ?: return false
            if (code == original) return false
            val r = release.uppercase()
            if (MULTI_AUDIO_RE.containsMatchIn(r) || MULTILANG_RE.containsMatchIn(r)) return false
            return true
        }

        private val MULTILANG_RE = Regex("""\b(MULTI|MULTILANG|MULTILANGUAGE|DUAL)\b""")
        private val CYRILLIQUE = Regex("""[\u0400-\u04FF]""")

        /** Mots de release qui désignent une langue audio, du plus fréquent au plus rare. */
        private val LANGUES_RELEASE: List<Pair<Regex, Pair<String, String>>> = listOf(
            Regex("""\b(SPANISH|CASTELLANO|ESPANOL|ESPAÑOL|LATINO)\b""") to ("es" to "espagnol"),
            Regex("""\b(ITA|ITALIAN|ITALIANO)\b""") to ("it" to "italien"),
            Regex("""\b(GERMAN|DEUTSCH)\b""") to ("de" to "allemand"),
            Regex("""\b(RUS|RUSSIAN)\b""") to ("ru" to "russe"),
            Regex("""\b(JAP|JAPANESE|JPN)\b""") to ("ja" to "japonais"),
            Regex("""\b(KOR|KOREAN)\b""") to ("ko" to "coréen"),
            Regex("""\b(HINDI|HIN)\b""") to ("hi" to "hindi"),
            Regex("""\b(PORTUGUESE|DUBLADO|PT-BR|PTBR)\b""") to ("pt" to "portugais"),
            Regex("""\b(TURKISH|TUR)\b""") to ("tr" to "turc"),
            Regex("""\b(POLISH|LEKTOR)\b""") to ("pl" to "polonais"),
            Regex("""\b(DUTCH|NLSUBBED)\b""") to ("nl" to "néerlandais"),
            Regex("""\b(ENGLISH|ENG)\b""") to ("en" to "anglais"),
        )
    }
}
