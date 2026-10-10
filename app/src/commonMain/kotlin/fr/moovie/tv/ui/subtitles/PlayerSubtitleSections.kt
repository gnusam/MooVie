package fr.moovie.tv.ui.subtitles

import fr.moovie.tv.resources.player_subtitles_translating_short
import fr.moovie.tv.resources.player_subtitles_translated
import androidx.compose.runtime.Composable
import fr.moovie.tv.core.subtitles.model.SubtitleCandidate
import fr.moovie.tv.core.subtitles.usecase.COMMON_FPS
import fr.moovie.tv.core.subtitles.usecase.orderForMenu
import fr.moovie.tv.resources.player_subtitles_tried
import fr.moovie.tv.resources.player_subtitles_try_next
import fr.moovie.tv.resources.Res
import fr.moovie.tv.resources.player_online_subtitles
import fr.moovie.tv.resources.player_subtitles_downloaded
import fr.moovie.tv.resources.player_subtitles_translate
import fr.moovie.tv.resources.player_subtitles_translate_title
import fr.moovie.tv.resources.player_subtitles_translating
import fr.moovie.tv.resources.player_subtitles_earlier
import fr.moovie.tv.resources.player_subtitles_fps
import fr.moovie.tv.resources.player_subtitles_fps_auto
import fr.moovie.tv.resources.player_subtitles_later
import fr.moovie.tv.resources.player_subtitles_network
import fr.moovie.tv.resources.player_subtitles_none_found
import fr.moovie.tv.resources.player_subtitles_offset
import fr.moovie.tv.resources.player_subtitles_quota_left
import fr.moovie.tv.resources.player_subtitles_quota_out
import fr.moovie.tv.resources.player_subtitles_searching
import fr.moovie.tv.resources.player_subtitles_sync
import fr.moovie.tv.resources.player_subtitles_sync_reset
import fr.moovie.tv.ui.player.PlayerOption
import fr.moovie.tv.ui.player.PlayerOptionSection
import org.jetbrains.compose.resources.stringResource

/**
 * Sections « sous-titres en ligne » de la modale du lecteur.
 *
 * Elles réutilisent le modèle d'options déjà en place plutôt que d'inventer une
 * fenêtre à part : c'est la même mécanique de focus, éprouvée à la télécommande,
 * et les deux lecteurs les ajoutent à l'identique.
 */

/**
 * Liste des sous-titres proposés.
 *
 * Chaque ligne affiche **la langue, la cadence et le nom de la release**, et pas
 * seulement la langue. La sonde avait montré pourquoi : les mieux classés pour
 * Fight Club étaient des sous-titres d'une autre série, mal étiquetés sous le
 * même identifiant TMDB. Aucun critère automatique ne détecte ça — seul le nom
 * de la release trahit, et l'utilisateur doit l'avoir sous les yeux **avant** de
 * dépenser un téléchargement.
 */
@Composable
fun onlineSubtitleSection(
    state: PlayerSubtitlesState,
    onNext: () -> Unit = {},
    onPick: (SubtitleCandidate) -> Unit,
): PlayerOptionSection {
    val title = buildString {
        append(stringResource(Res.string.player_online_subtitles))
        state.quota.remaining?.let {
            append(" — ")
            append(stringResource(Res.string.player_subtitles_quota_left, it))
        }
    }

    val options = when {
        state.loading -> listOf(PlayerOption(stringResource(Res.string.player_subtitles_searching), false) {})
        state.trouble == SubtitleTrouble.QUOTA ->
            listOf(PlayerOption(stringResource(Res.string.player_subtitles_quota_out), false) {})
        state.trouble == SubtitleTrouble.NETWORK ->
            listOf(PlayerOption(stringResource(Res.string.player_subtitles_network), false) {})
        state.candidates.isEmpty() ->
            listOf(PlayerOption(stringResource(Res.string.player_subtitles_none_found), false) {})
        else -> {
            val mark = stringResource(Res.string.player_subtitles_downloaded)
            val traduit = stringResource(Res.string.player_subtitles_translated)
            val enCours = stringResource(Res.string.player_subtitles_translating_short)
            val essaye = stringResource(Res.string.player_subtitles_tried)
            val activeId = state.active?.fileId
            // « Essayer le suivant » en tête tant qu'il en reste : une touche
            // pour enchaîner, sans relire des noms de release illisibles.
            val restants = state.candidates.count { it.fileId !in state.tried && it.fileId != activeId }
            val suivant = if (restants > 0) {
                listOf(PlayerOption(stringResource(Res.string.player_subtitles_try_next, restants), false) { onNext() })
            } else {
                emptyList()
            }
            suivant + orderForMenu(state.candidates, activeId, state.tried).take(MAX_SHOWN).map { candidate ->
                val paid = candidate.fileId in state.downloaded
                val actif = candidate.fileId == activeId
                val dejaEssaye = !actif && candidate.fileId in state.tried
                // L'état de la traduction se lit sur la ligne active : c'est là
                // qu'on regarde pour savoir ce qui s'affiche.
                val etat = when {
                    !actif -> null
                    state.translated != null -> enCours
                    state.translatedDone -> traduit
                    else -> null
                }
                PlayerOption(
                    // Le repère passe **en tête** : c'est l'information la plus
                    // actionnable de la ligne — gratuit ou non — et la seule que
                    // la troncature de la release ne doit jamais emporter.
                    label = listOfNotNull(
                        mark.takeIf { paid },
                        candidate.describe(),
                        etat,
                        essaye.takeIf { dejaEssaye },
                    ).joinToString(" · "),
                    selected = actif,
                ) { onPick(candidate) }
            }
        }
    }
    return PlayerOptionSection(title, options)
}

/**
 * Traduire en français le sous-titre affiché.
 *
 * Rend `null` — donc rien à l'écran — sans clé d'API ou sans sous-titre actif.
 * Une action grisée ferait croire à une panne là où il n'y a qu'une fonction
 * non configurée.
 *
 * Pendant la traduction, la même ligne devient un compteur. Elle reste dans la
 * liste plutôt que de disparaître : c'est le seul endroit où l'utilisateur peut
 * vérifier que quelque chose avance, et l'attente se compte en dizaines de
 * secondes sur un long film.
 */
@Composable
fun subtitleTranslationSection(
    state: PlayerSubtitlesState,
    onTranslate: () -> Unit,
): PlayerOptionSection? {
    if (!state.canTranslate || state.active == null) return null
    val progres = state.translated
    val option = when {
        progres != null -> PlayerOption(
            stringResource(Res.string.player_subtitles_translating, progres.first, progres.second),
            selected = false,
        ) {}
        // Fait : la ligne le dit, au lieu de reproposer « Traduire » comme si
        // rien ne s'était passé.
        state.translatedDone -> PlayerOption(stringResource(Res.string.player_subtitles_translated), selected = true) {}
        else -> PlayerOption(stringResource(Res.string.player_subtitles_translate), false) { onTranslate() }
    }
    return PlayerOptionSection(
        stringResource(Res.string.player_subtitles_translate_title),
        listOf(option),
    )
}

/**
 * Réglage de synchronisation : décalage par pas, et remise à zéro.
 *
 * Des pas plutôt qu'un curseur, parce qu'à la télécommande un curseur se règle
 * mal — et parce que chaque changement réécrit le fichier, ce qui côté Media3
 * recharge le flux. Un nombre borné d'appuis vaut mieux qu'un glissement continu.
 */
@Composable
fun subtitleSyncSection(
    state: PlayerSubtitlesState,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
): PlayerOptionSection {
    if (state.active == null) return PlayerOptionSection("", emptyList())

    val offsetSeconds = state.timing.offsetMs / 1000.0
    val title = stringResource(Res.string.player_subtitles_sync) +
        " — " + stringResource(Res.string.player_subtitles_offset, format(offsetSeconds))

    val options = buildList {
        NUDGES.forEach { step ->
            val label = if (step < 0) {
                stringResource(Res.string.player_subtitles_earlier, format(-step / 1000.0))
            } else {
                stringResource(Res.string.player_subtitles_later, format(step / 1000.0))
            }
            add(PlayerOption(label, false) { onNudge(step) })
        }
        add(PlayerOption(stringResource(Res.string.player_subtitles_sync_reset), false) { onReset() })
    }
    return PlayerOptionSection(title, options)
}

/**
 * Cadence d'origine du sous-titre, à forcer quand le catalogue ne la déclare
 * pas — c'était le cas de 14 % des résultats mesurés. Sans elle, la dérive
 * progressive ne peut pas être corrigée autrement qu'à tâtons.
 */
@Composable
fun subtitleFpsSection(
    state: PlayerSubtitlesState,
    onAssume: (Double) -> Unit,
): PlayerOptionSection {
    if (state.active == null) return PlayerOptionSection("", emptyList())

    val declared = state.active.fps
    val options = buildList {
        add(
            PlayerOption(
                stringResource(Res.string.player_subtitles_fps_auto) +
                    (declared?.let { " (${format(it)})" } ?: ""),
                !state.timing.scaled,
            ) { onAssume(0.0) },
        )
        COMMON_FPS.forEach { fps ->
            add(PlayerOption(format(fps), false) { onAssume(fps) })
        }
    }
    return PlayerOptionSection(stringResource(Res.string.player_subtitles_fps), options)
}

/** « fr · 23.976 · Fight.Club.2160p.BluRay » — tout ce qui aide à trancher. */
private fun SubtitleCandidate.describe(): String = buildString {
    append(language.uppercase())
    fps?.let { append(" · ").append(format(it)) }
    if (foreignPartsOnly) append(" · forcé")
    if (hearingImpaired) append(" · SDH")
    if (release.isNotBlank()) append(" · ").append(release.take(36))
}

/** Deux décimales au plus, sans zéro inutile : « 23.976 », « 1.5 », « 25 ». */
private fun format(value: Double): String =
    value.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }

/**
 * Pas de décalage proposés. Bornés volontairement : au-delà de deux secondes il
 * ne s'agit plus d'un décalage mais d'un mauvais sous-titre, ou d'une cadence à
 * corriger — deux problèmes que ce réglage ne résout pas.
 */
private val NUDGES = listOf(-2_000L, -500L, 500L, 2_000L)

/**
 * La liste peut compter cent entrées ; au D-pad, en descendre cinquante est
 * hors de question. Le classement fait le travail, on ne montre que sa tête.
 */
private const val MAX_SHOWN = 12
