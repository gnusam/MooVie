package fr.moovie.tv.ui.subtitles

import fr.moovie.tv.data.tmdb.TmdbRepository
import fr.moovie.tv.data.subtitles.SubtitleChoiceRepository
import fr.moovie.tv.shared.Chrono
import fr.moovie.tv.shared.dispatcherEs
import fr.moovie.tv.shared.maintenantMs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.moovie.tv.core.sources.model.MediaRef
import fr.moovie.tv.core.sources.model.PlayableStream
import fr.moovie.tv.core.subtitles.model.VideoIdentity
import fr.moovie.tv.data.sources.ExtractorRegistry
import fr.moovie.tv.data.subtitles.videoIdentity
import io.ktor.http.decodeURLPart
import fr.moovie.tv.core.subtitles.model.SubtitleCandidate
import fr.moovie.tv.core.subtitles.model.SubtitleQuota
import fr.moovie.tv.core.subtitles.usecase.SubtitleTiming
import fr.moovie.tv.core.subtitles.usecase.rankSubtitles
import fr.moovie.tv.core.subtitles.usecase.timingFor
import fr.moovie.tv.data.settings.SettingsRepository
import fr.moovie.tv.core.subtitles.port.SubtitleCatalog
import fr.moovie.tv.data.subtitles.StremioSubtitleCatalog
import fr.moovie.tv.core.subtitles.usecase.parseSrt
import fr.moovie.tv.core.subtitles.usecase.renderSrt
import fr.moovie.tv.core.subtitles.usecase.translateCues
import fr.moovie.tv.data.subtitles.ClaudeSubtitleTranslator
import fr.moovie.tv.data.subtitles.TraductionConfig
import fr.moovie.tv.data.subtitles.ChainedSubtitleCatalog
import fr.moovie.tv.data.subtitles.OpenSubtitlesApi
import fr.moovie.tv.data.subtitles.OpenSubtitlesCatalog
import fr.moovie.tv.data.subtitles.OpenSubtitlesSession
import fr.moovie.tv.data.subtitles.SubtitleFileStore
import fr.moovie.tv.shared.openSubtitlesApiKey
import fr.moovie.tv.ui.player.parseMediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path

/** Ce qui a empêché d'aboutir, en termes affichables. */
enum class SubtitleTrouble { QUOTA, NETWORK, NOTHING_FOUND }

data class PlayerSubtitlesState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val candidates: List<SubtitleCandidate> = emptyList(),
    val active: SubtitleCandidate? = null,
    /** Identifiants déjà sur disque : les rejouer ne coûte rien. */
    val downloaded: Set<String> = emptySet(),
    /** Identifiants déjà activés sur ce titre, à marquer et à ranger après les autres. */
    val tried: Set<String> = emptySet(),
    val timing: SubtitleTiming = SubtitleTiming.None,
    val quota: SubtitleQuota = SubtitleQuota.Unknown,
    val trouble: SubtitleTrouble? = null,
    /**
     * La traduction est-elle proposable ? Faux sans clé d'API : l'action ne
     * s'affiche pas du tout, plutôt que d'echouer une fois touchée.
     */
    val canTranslate: Boolean = false,
    /**
     * Répliques déjà traduites sur le total, ou null hors traduction.
     *
     * Deux nombres et pas un pourcentage : ce qui rassure devant une attente
     * d'une minute, c'est de voir un compteur avancer, et le total dit d'emblée
     * l'ordre de grandeur de ce qui reste.
     */
    val translated: Pair<Int, Int>? = null,
    /** Le sous-titre actif est la traduction française, achevée. */
    val translatedDone: Boolean = false,
)

/**
 * Sous-titres en ligne, pendant la lecture.
 *
 * Deux principes gouvernent cette classe, et ils viennent tous deux de la
 * rareté du quota :
 *
 * **La recherche est automatique, le téléchargement jamais.** Chercher ne coûte
 * rien et sans liste il n'y a rien à choisir ; télécharger coûte une unité d'un
 * quota qui se compte sur les doigts d'une main, et n'arrive donc que sur une
 * validation explicite.
 *
 * **Un sous-titre déjà payé ne se repaie pas.** Le fichier d'origine est gardé
 * sur disque : changer de décalage, corriger la cadence ou revenir à ce
 * sous-titre après en avoir essayé un autre ne redéclenche aucun appel réseau.
 */
class PlayerSubtitlesViewModel : ViewModel() {

    private val settings = SettingsRepository()
    private val api = OpenSubtitlesApi(openSubtitlesApiKey)
    private val session = OpenSubtitlesSession(api, settings)
    /**
     * OpenSubtitles d'abord, l'addon Stremio quand il ne rend rien.
     *
     * Les deux cas où le second sert : une compilation sans clé, où le premier
     * se déclare indisponible ; et un titre pour lequel il ne trouve rien alors
     * que l'autre fonds l'a. Voir [ChainedSubtitleCatalog] pour ce que la
     * cascade garantit, et [StremioSubtitleCatalog] pour ses limites.
     */
    private val catalog: SubtitleCatalog =
        ChainedSubtitleCatalog(listOf(OpenSubtitlesCatalog(api), StremioSubtitleCatalog()))
    private val files = SubtitleFileStore()
    private val choix = SubtitleChoiceRepository()

    /**
     * Traducteur à la demande. Inerte sans clé d'API — voir [TraductionConfig] —
     * et l'écran ne propose alors rien du tout.
     */
    private val translator = ClaudeSubtitleTranslator()

    private val _state = MutableStateFlow(
        PlayerSubtitlesState(
            available = catalog.available,
            canTranslate = translator.configured,
        ),
    )
    val state: StateFlow<PlayerSubtitlesState> = _state.asStateFlow()

    /**
     * Fichier à donner au lecteur, déjà recalé. Null = aucun sous-titre externe.
     * L'écran l'observe et le transmet au contrôleur : le ViewModel n'a pas à
     * connaître le lecteur.
     */
    private val _file = MutableStateFlow<String?>(null)
    val file: StateFlow<String?> = _file.asStateFlow()

    private var mediaKey: String = ""
    private var original: Path? = null
    private var streamFps: Double? = null

    /**
     * Cherche les sous-titres du média en cours. [title] sert de repli quand la
     * clé de lecture ne porte pas d'identifiant TMDB exploitable.
     */
    fun load(
        mediaKey: String,
        title: String,
        streamFps: Double,
        episodeTitle: String = "",
        stream: PlayableStream? = null,
    ) = io {
        // Idempotent : les écrans peuvent l'appeler à chaque ouverture du menu.
        // Ce n'est plus à eux de deviner si la liste est celle du bon épisode.
        if (this.mediaKey == mediaKey && _state.value.candidates.isNotEmpty()) return@io
        search(mediaKey, title, streamFps, episodeTitle, stream)
    }

    /**
     * L'empreinte du fichier joué, calculée une fois par média et par flux :
     * deux lectures de 64 Kio, inutiles à refaire à chaque ouverture du menu.
     */
    private var empreinte: Triple<String, String, VideoIdentity?>? = null

    private suspend fun empreinteDe(mediaKey: String, stream: PlayableStream?): VideoIdentity? {
        val flux = stream ?: return null
        empreinte?.takeIf { it.first == mediaKey && it.second == flux.url }?.let { return it.third }
        val calculee = videoIdentity(ExtractorRegistry.gateway, flux)
        empreinte = Triple(mediaKey, flux.url, calculee)
        return calculee
    }

    /**
     * Le lecteur présente un média : s'il change, on repart de zéro.
     *
     * Le ViewModel est porté par l'activité et survit à la navigation d'un
     * épisode à l'autre. Mesuré le 09/10/2026 (Sam : « l'épisode 2 me propose
     * des sous-titres de l'épisode 1 ») : la liste, le sous-titre actif et sa
     * traduction de l'épisode précédent restaient en place, et le menu ne
     * cherchait plus puisque sa liste n'était pas vide.
     */
    fun enter(mediaKey: String) {
        if (this.mediaKey.isNotEmpty() && this.mediaKey != mediaKey) {
            Chrono.repere("sous-titres : nouveau média ${this.mediaKey} -> $mediaKey, remise à zéro")
            reset()
        }
    }

    private suspend fun search(
        mediaKey: String,
        title: String,
        streamFps: Double,
        episodeTitle: String = "",
        stream: PlayableStream? = null,
    ) {
        if (!_state.value.available) return
        enter(mediaKey)
        this.mediaKey = mediaKey
        this.streamFps = streamFps.takeIf { it > 0 }

        val id = parseMediaKey(mediaKey) ?: return
        val media = if (id.isTv) {
            MediaRef.Episode(id.tmdbId, title, season = id.season, episode = id.episode)
        } else {
            MediaRef.Movie(id.tmdbId, title)
        }

        _state.value = _state.value.copy(loading = true, trouble = null)
        session.restore()
        session.renewIfNeeded(maintenantMs())

        val languages = settings.subtitleLanguages.first()
        // L'empreinte du fichier, puis la recherche avec : les dépôts faits
        // pour ce fichier exact reviennent marqués et passent en tête.
        val identite = empreinteDe(mediaKey, stream)
        val found = catalog.search(media, languages, identite)
        val nomFichier = stream?.url?.substringBefore('?')?.substringAfterLast('/')
            ?.let { runCatching { it.decodeURLPart() }.getOrDefault(it) }
        // Le titre anglais de l'épisode, pour reconnaître les releases : TMDB
        // est interrogé en français partout ailleurs. Un appel, en échec
        // silencieux — le classement se passe de lui, en moins bien.
        val titreAnglais = if (id.isTv && episodeTitle.isNotBlank()) {
            runCatching {
                TmdbRepository("en-US").season(settings.tmdbApiKey.first(), id.tmdbId, id.season)
                    .episodes.firstOrNull { it.episodeNumber == id.episode }?.name
            }.getOrNull()
        } else {
            null
        }
        Chrono.repere("sous-titres : titre de l'épisode « $episodeTitle », en anglais « ${titreAnglais.orEmpty()} »")
        Chrono.repere(
            "sous-titres : recherche $mediaKey ($media) -> ${found.size} candidats, " +
                "${found.count { it.hashMatch }} à l'empreinte, fichier « ${nomFichier.orEmpty()} » ; premiers : " +
                found.take(3).joinToString(" | ") { "${it.language} ${it.fileId} ${it.release.take(40)}" },
        )
        _state.value = _state.value.copy(
            loading = false,
            // Les deux préférences de contenu passent ici, et nulle part
            // ailleurs : c'est le classement qui décide quel fichier l'utilisateur
            // se verra proposer en premier, et à cinq téléchargements par jour ce
            // premier rang est presque toute la fonctionnalité.
            candidates = rankSubtitles(
                candidates = found,
                preferredLanguages = languages,
                streamFps = this.streamFps,
                preferHearingImpaired = settings.subtitlePreferHearingImpaired.first(),
                preferForced = settings.subtitlePreferForced.first(),
                episodeTitle = episodeTitle.takeIf { it.isNotBlank() },
                episodeTitles = listOfNotNull(titreAnglais?.takeIf { it.isNotBlank() }),
                sourceRelease = nomFichier,
            ),
            trouble = if (found.isEmpty()) SubtitleTrouble.NOTHING_FOUND else null,
            quota = catalog.quota(),
            downloaded = files.storedIds(mediaKey),
            tried = choix.tried(mediaKey),
        )
    }

    /**
     * Le prochain sous-titre pas encore essayé, dans l'ordre du classement :
     * une seule touche pour passer au suivant, sans relire la liste.
     */
    fun next() {
        val s = _state.value
        val suivant = s.candidates.firstOrNull { it.fileId !in s.tried && it.fileId != s.active?.fileId }
            ?: return
        Chrono.repere("sous-titres : suivant non essayé ${suivant.language} ${suivant.fileId}")
        pick(suivant)
    }

    /**
     * Active un sous-titre. Ne consomme le quota que si le fichier n'a pas déjà
     * été téléchargé pour ce média.
     */
    fun pick(candidate: SubtitleCandidate) = io {
        // Retenu avant même d'être activé : c'est le geste qui compte, et il
        // doit survivre à la sortie du lecteur. Voir SubtitleChoiceRepository.
        choix.remember(mediaKey, candidate)
        Chrono.repere("sous-titres : choix manuel ${candidate.language} ${candidate.fileId} retenu pour $mediaKey")
        activerEtTraduire(candidate)
    }

    /**
     * Active un sous-titre et, s'il n'est pas en français, le traduit — ou
     * ressert la traduction déjà faite, qui est restée sur disque : chaque
     * reprise retraduisait l'épisode entier (377 répliques, 20 s, un appel
     * d'API par lot) pour un résultat identique.
     */
    private suspend fun activerEtTraduire(candidate: SubtitleCandidate) {
        if (!activate(candidate)) return
        // Essayé, donc : marqué dans le menu et retenu pour ce titre.
        _state.value = _state.value.copy(tried = _state.value.tried + candidate.fileId)
        choix.markTried(mediaKey, candidate.fileId)
        if (candidate.language == "fr") return
        val deja = files.existingTranslated(mediaKey, candidate)
        if (deja != null) {
            original = deja
            _state.value = _state.value.copy(translated = null, translatedDone = true)
            Chrono.repere("sous-titres : traduction réutilisée $deja")
            publish(_state.value.timing)
            return
        }
        translateActive()
    }

    /** Rend false quand le quota a refusé le téléchargement. */
    private suspend fun activate(candidate: SubtitleCandidate): Boolean {
        val cached = files.existing(mediaKey, candidate)
        val source = cached ?: run {
            _state.value = _state.value.copy(loading = true, trouble = null)
            val downloaded = catalog.download(candidate)
            if (downloaded == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    trouble = SubtitleTrouble.QUOTA,
                    quota = catalog.quota(),
                )
                return false
            }
            _state.value = _state.value.copy(quota = downloaded.quota)
            files.storeOriginal(mediaKey, candidate, downloaded.content)
        }

        original = source
        Chrono.repere("sous-titres : activation ${candidate.language} ${candidate.fileId} « ${candidate.release.take(40)} » pour $mediaKey, cache=${cached != null} -> $source")
        // Un autre sous-titre est une autre traduction : le compteur du
        // precedent n'a plus de sens et laisserait l'ecran en « traduction en
        // cours » sur un fichier qui ne l'est pas.
        _state.value = _state.value.copy(translated = null, translatedDone = false)
        // La correction de cadence s'applique d'emblée : c'est la dérive qu'un
        // curseur de décalage ne rattrape pas, autant partir juste.
        val timing = timingFor(candidate.fps, streamFps)
        _state.value = _state.value.copy(
            active = candidate,
            timing = timing,
            loading = false,
            downloaded = files.storedIds(mediaKey),
        )
        publish(timing)
        return true
    }

    /**
     * Traduit le sous-titre actif vers le français, **pendant la lecture**.
     *
     * Chaque lot republie un fichier entier : le lecteur le recharge par le
     * chemin habituel, celui qui sert déjà au décalage et à la cadence. Rien de
     * neuf côté lecteur, donc, et la traduction apparaît par le début du film —
     * qui est justement ce qu'on est en train de regarder.
     *
     * Relancer sur un sous-titre déjà traduit repart de **l'original** conservé
     * à côté : sans ça, la deuxième passe traduirait du français vers du
     * français.
     */
    fun translate() = io { translateActive() }

    /**
     * Génération de la traduction en cours : changer de sous-titre l'incrémente,
     * et la traduction qui tourne encore s'arrête au lot suivant au lieu de
     * republier son fichier. Mesuré le 10/10/2026 sur One Piece : la piste
     * intégrée choisie par Sam était effacée vingt secondes plus tard par la
     * traduction du sous-titre en ligne qu'il venait de quitter (« quand je le
     * sélectionne, ça affiche rien du tout »).
     */
    private var traductionGen = 0

    private suspend fun translateActive() {
        val candidate = _state.value.active ?: return
        val source = original ?: return
        if (!translator.configured || _state.value.translated != null) return
        val gen = ++traductionGen

        val cues = parseSrt(files.read(source) ?: return)
        if (cues.isEmpty()) return
        Chrono.repere("sous-titres : traduction de $source, ${cues.size} répliques")
        _state.value = _state.value.copy(translated = 0 to cues.size)

        // Le compteur avance à chaque lot, mais le lecteur ne recharge le
        // fichier qu'au premier lot puis toutes les RECHARGE_MS au plus : un
        // sous-titre externe remonte le flux, et avec des lots parallèles neuf
        // rechargements tombaient en vingt secondes.
        var publieA = 0L
        val traduits = translateCues(cues, translator, parallelism = LOTS_EN_VOL) { partiel, faites ->
            if (gen != traductionGen) throw CancellationException("sous-titre changé pendant la traduction")
            val fichier = files.storeTranslated(mediaKey, candidate, renderSrt(partiel), complete = false)
            original = fichier
            _state.value = _state.value.copy(translated = faites to cues.size)
            val maintenant = maintenantMs()
            if (publieA == 0L || maintenant - publieA >= RECHARGE_MS) {
                publieA = maintenant
                publish(_state.value.timing)
            }
        }
        if (gen != traductionGen) return
        original = files.storeTranslated(mediaKey, candidate, renderSrt(traduits), complete = true)
        _state.value = _state.value.copy(translated = null, translatedDone = true)
        Chrono.repere("sous-titres : traduction finie -> $original")
        publish(_state.value.timing)
    }

    /**
     * Des sous-titres français sans rien demander, pour une lecture en VO.
     *
     * Sam, 09/10/2026 : « priorité sur les sous-titres en français ». Le menu
     * savait déjà chercher, activer et traduire, mais à la main : une VO
     * démarrait sur la piste anglaise intégrée au fichier. Ici, au lancement :
     * un sous-titre français s'il en existe un, sinon le meilleur anglais
     * aussitôt traduit — par le début, ce qu'on regarde. Fullmetal Alchemist
     * (2003) n'a aucun sous-titre français, ni sur OpenSubtitles ni sur
     * l'addon : sans la traduction, il n'y avait rien à proposer.
     *
     * Ne fait rien si un sous-titre est déjà actif : un choix fait dans le
     * menu, ou une reprise, ne se fait pas écraser.
     */
    fun autoFrench(
        mediaKey: String,
        title: String,
        streamFps: Double,
        episodeTitle: String = "",
        stream: PlayableStream? = null,
    ) = io {
        if (_state.value.active != null) {
            Chrono.repere("sous-titres : auto $mediaKey, déjà actif ${_state.value.active?.fileId}, rien à faire")
            return@io
        }
        // Le choix de l'utilisateur sur ce titre passe avant toute recherche.
        when (val souvenir = choix.get(mediaKey)) {
            is SubtitleChoiceRepository.Remembered.Off -> {
                Chrono.repere("sous-titres : auto $mediaKey, coupés par l'utilisateur, rien à faire")
                return@io
            }
            is SubtitleChoiceRepository.Remembered.Candidate -> {
                Chrono.repere("sous-titres : auto $mediaKey, choix retenu ${souvenir.candidate.language} ${souvenir.candidate.fileId}")
                if (this.mediaKey != mediaKey) { enter(mediaKey); this.mediaKey = mediaKey }
                activerEtTraduire(souvenir.candidate)
                if (_state.value.active != null) return@io
                Chrono.repere("sous-titres : choix retenu introuvable, recherche normale")
            }
            null -> Unit
        }
        if (_state.value.candidates.isEmpty() || this.mediaKey != mediaKey) {
            search(mediaKey, title, streamFps, episodeTitle, stream)
        }
        val candidates = _state.value.candidates
        val choisi = candidates.firstOrNull { it.language == "fr" }
            ?: candidates.firstOrNull { it.language == "en" }.takeIf { translator.configured }
        if (choisi == null) {
            Chrono.repere("sous-titres : auto $mediaKey, aucun candidat fr ni en")
            return@io
        }
        Chrono.repere("sous-titres : auto $mediaKey, choisi ${choisi.language} ${choisi.fileId}")
        activerEtTraduire(choisi)
    }

    /** Décale le sous-titre, en relatif. */
    fun nudge(deltaMs: Long) {
        val timing = _state.value.timing
        apply(timing.copy(offsetMs = timing.offsetMs + deltaMs))
    }

    /** Force la cadence d'origine du sous-titre, quand elle n'est pas déclarée. */
    fun assumeSubtitleFps(fps: Double) {
        apply(timingFor(fps, streamFps, _state.value.timing.offsetMs))
    }

    fun resetTiming() = apply(SubtitleTiming(offsetMs = 0, scale = 1.0))

    /**
     * Retire le sous-titre externe sans oublier ce qui a été téléchargé, et
     * **sans rien retenir** : c'est ce qu'il se passe quand on choisit une
     * piste intégrée du fichier, qui remplace la nôtre à l'écran mais ne dit
     * pas « plus de sous-titres ». Mesuré le 10/10/2026 sur One Piece : un
     * appui sur une piste intégrée était retenu comme « coupés par
     * l'utilisateur », et l'épisode ne recevait plus jamais le français
     * d'office (« sous-titres : auto …, coupés par l'utilisateur »).
     */
    fun clear() {
        traductionGen++
        original = null
        _state.value = _state.value.copy(active = null, timing = SubtitleTiming.None, translated = null)
        _file.value = null
    }

    /** « Désactivés », choisi dans le menu : un choix comme un autre, retenu pour ce titre. */
    fun turnOff() {
        viewModelScope.launch { choix.rememberOff(mediaKey) }
        clear()
    }

    /** À la sortie du lecteur : les versions dérivées n'ont plus d'utilité. */
    fun onLeave() {
        files.clearDerived(mediaKey)
    }

    /**
     * Change de média **sans** quitter le lecteur.
     *
     * L'enchaînement d'épisodes garde désormais le lecteur monté — c'est tout
     * l'objet du remplacement sur place — et donc ce ViewModel avec lui. Sans
     * cette remise à zéro, l'épisode suivant héritait de trois choses du
     * précédent : les candidats déjà listés (le menu ne cherchait donc plus,
     * `load` ne s'exécutant que sur une liste vide), le fichier recalé publié
     * dans [file], et la clé de média sous laquelle les téléchargements se
     * rangent — un sous-titre payé pour l'épisode 3 aurait été classé sous
     * l'épisode 2, et repayé à la prochaine écoute.
     *
     * Les fichiers d'origine restent sur disque : c'est [onLeave] qui ne
     * balaie que les versions dérivées, et un sous-titre déjà payé ne doit pas
     * disparaître parce qu'on a changé d'épisode.
     */
    fun reset() {
        traductionGen++
        Chrono.repere("sous-titres : reset (média $mediaKey)")
        onLeave()
        original = null
        mediaKey = ""
        streamFps = null
        _state.value = PlayerSubtitlesState(available = openSubtitlesApiKey.isNotBlank())
        _file.value = null
    }

    private fun apply(timing: SubtitleTiming) = io {
        if (original == null) return@io
        _state.value = _state.value.copy(timing = timing)
        publish(timing)
    }

    private fun publish(timing: SubtitleTiming) {
        val source = original ?: return
        // `okio.Path.toString()` rend le chemin natif de la plateforme, ce
        // que `File.absolutePath` rendait déjà : le lecteur reçoit la même
        // chaîne qu'avant.
        val chemin = files.retimed(source, timing).toString()
        Chrono.repere("sous-titres : publication $chemin")
        _file.value = chemin
    }

    private companion object {
        /** Lots traduits en même temps : mesuré à ~5 s le lot de 40 sur Haiku. */
        const val LOTS_EN_VOL = 3

        /** Écart minimal entre deux rechargements du fichier dans le lecteur. */
        const val RECHARGE_MS = 20_000L
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(dispatcherEs) { block() } }
    }
}
