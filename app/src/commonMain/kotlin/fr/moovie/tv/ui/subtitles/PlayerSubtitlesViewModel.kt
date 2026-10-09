package fr.moovie.tv.ui.subtitles

import fr.moovie.tv.shared.dispatcherEs
import fr.moovie.tv.shared.maintenantMs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.moovie.tv.core.sources.model.MediaRef
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
    fun load(mediaKey: String, title: String, streamFps: Double) = io { search(mediaKey, title, streamFps) }

    private suspend fun search(mediaKey: String, title: String, streamFps: Double) {
        if (!_state.value.available) return
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
        val found = catalog.search(media, languages)
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
            ),
            trouble = if (found.isEmpty()) SubtitleTrouble.NOTHING_FOUND else null,
            quota = catalog.quota(),
            downloaded = files.storedIds(mediaKey),
        )
    }

    /**
     * Active un sous-titre. Ne consomme le quota que si le fichier n'a pas déjà
     * été téléchargé pour ce média.
     */
    fun pick(candidate: SubtitleCandidate) = io { activate(candidate) }

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
        // Un autre sous-titre est une autre traduction : le compteur du
        // precedent n'a plus de sens et laisserait l'ecran en « traduction en
        // cours » sur un fichier qui ne l'est pas.
        _state.value = _state.value.copy(translated = null)
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

    private suspend fun translateActive() {
        val candidate = _state.value.active ?: return
        val source = original ?: return
        if (!translator.configured || _state.value.translated != null) return

        val cues = parseSrt(files.read(source) ?: return)
        if (cues.isEmpty()) return
        _state.value = _state.value.copy(translated = 0 to cues.size)

        val traduits = translateCues(cues, translator) { partiel, faites ->
            val fichier = files.storeTranslated(mediaKey, candidate, renderSrt(partiel))
            original = fichier
            _state.value = _state.value.copy(translated = faites to cues.size)
            publish(_state.value.timing)
        }
        original = files.storeTranslated(mediaKey, candidate, renderSrt(traduits))
        _state.value = _state.value.copy(translated = null)
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
    fun autoFrench(mediaKey: String, title: String, streamFps: Double) = io {
        if (_state.value.active != null) return@io
        if (_state.value.candidates.isEmpty() || this.mediaKey != mediaKey) {
            search(mediaKey, title, streamFps)
        }
        val candidates = _state.value.candidates
        val choisi = candidates.firstOrNull { it.language == "fr" }
            ?: candidates.firstOrNull { it.language == "en" }.takeIf { translator.configured }
            ?: return@io
        if (!activate(choisi)) return@io
        if (choisi.language != "fr") translateActive()
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

    /** Retire le sous-titre externe sans oublier ce qui a été téléchargé. */
    fun clear() {
        original = null
        _state.value = _state.value.copy(active = null, timing = SubtitleTiming.None)
        _file.value = null
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
        _file.value = files.retimed(source, timing).toString()
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(dispatcherEs) { block() } }
    }
}
