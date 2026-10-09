package fr.moovie.tv.core.subtitles.usecase

import fr.moovie.tv.core.subtitles.model.SubtitleCandidate
import kotlin.math.abs

/**
 * Classe les sous-titres proposés, du plus probable au moins.
 *
 * **Le classement est la fonctionnalité, pas de la présentation.** À cinq
 * téléchargements par jour, chaque essai raté coûte un cinquième de la journée :
 * il faut que le premier de la liste soit le bon le plus souvent possible, parce
 * que l'utilisateur n'aura pas les moyens d'en essayer quatre.
 *
 * Critères, dans l'ordre :
 *
 * 1. **La langue**, selon la préférence déclarée. Un sous-titre dans la mauvaise
 *    langue n'est pas un mauvais candidat, c'en est un autre.
 * 2. **Le type de sous-titre** : forcé et sourds-malentendants reculent, sauf
 *    demande. C'est du *contenu*, donc ça passe avant la synchronisation — un
 *    sous-titre forcé parfaitement calé reste le mauvais fichier.
 * 3. **La traduction humaine** avant la machine : `machine_translated` puis
 *    `ai_translated` reculent, sans disparaître. Sur des titres peu diffusés,
 *    c'est parfois tout ce qui existe.
 * 4. **La cadence**, quand on connaît celle du flux. Elle vient après le
 *    contenu parce qu'elle se corrige — voir [timingFor] — alors qu'un mauvais
 *    contenu ne se rattrape pas.
 * 5. **La confiance et l'usage** : `from_trusted`, puis le nombre de
 *    téléchargements — le vote des autres, faute de mieux.
 *
 * @param streamFps cadence du flux joué, si le lecteur la connaît. Null la
 *   neutralise plutôt que de pénaliser tout le monde au hasard.
 * @param preferHearingImpaired remonte les sous-titres pour sourds et
 *   malentendants au lieu de les reculer.
 * @param preferForced remonte les sous-titres « forcés » (uniquement les
 *   passages en langue étrangère). Ce n'est pas un cas marginal : la sonde
 *   `ForcedSubtitleProbeTest` avait établi que **zéro flux sur 66** déclarait une
 *   piste forcée, et la fonctionnalité avait été classée impossible. Elle
 *   redevient atteignable par ici — mais sur demande seulement, un sous-titre
 *   forcé arrivant par défaut donnerait l'impression que les dialogues manquent.
 */
fun rankSubtitles(
    candidates: List<SubtitleCandidate>,
    preferredLanguages: List<String>,
    streamFps: Double? = null,
    preferHearingImpaired: Boolean = false,
    preferForced: Boolean = false,
    /**
     * Titre de l'épisode d'après TMDB, ou null pour un film.
     *
     * Une release qui le contient est classée avant toutes les autres de sa
     * langue, avant même la popularité. Mesuré le 09/10/2026 sur Fullmetal
     * Alchemist (2003) S01E02 : OpenSubtitles range sous cette série, avec
     * 22 591 téléchargements et des champs structurés corrects (série,
     * saison, épisode), un « Fullmetal.Alchemist.Brotherhood.E02 » — un
     * sous-titre de l'autre série, qui raconte l'épisode 1 de celle-ci. Seul
     * le nom de la release le trahit ; les deux bons candidats s'appelaient
     * « 02 - Body of the Sanctioned ».
     */
    episodeTitle: String? = null,
    /**
     * Le même titre dans d'autres langues, l'anglais surtout : l'application
     * interroge TMDB en français, et les releases d'OpenSubtitles sont nommées
     * en anglais — « Body of the Sanctioned », jamais « Le Corps des
     * sacrifiés ». Mesuré le 09/10/2026 à 23:06 : sans lui, le classement
     * reprenait le Brotherhood.
     */
    episodeTitles: List<String> = emptyList(),
): List<SubtitleCandidate> {
    val langOrder = preferredLanguages.map { it.lowercase() }
    val stream = normalizeFps(streamFps)
    val titres = (listOfNotNull(episodeTitle) + episodeTitles)
        .map(::normalizeTitle)
        .filter { it.length >= 4 }

    return candidates.sortedWith(
        compareBy<SubtitleCandidate> { candidate ->
            // Langue inconnue de la préférence : après toutes celles qui y sont.
            langOrder.indexOf(candidate.language.lowercase())
                .takeIf { it >= 0 } ?: langOrder.size
        }
            // Le *type* de sous-titre avant sa synchronisation : un « forcé »
            // parfaitement calé reste le mauvais contenu, là où une cadence qui
            // ne correspond pas se corrige. Constaté sur la sonde, où un
            // « Fight.Club.Forced » sortait en tête parce qu'il était le seul à
            // la bonne cadence.
            .thenBy { candidate ->
                val release = normalizeTitle(candidate.release)
                if (titres.any { release.contains(it) }) 0 else 1
            }
            .thenBy { if (it.foreignPartsOnly == preferForced) 0 else 1 }
            .thenBy { if (it.hearingImpaired == preferHearingImpaired) 0 else 1 }
            .thenBy { if (it.machineTranslated) 1 else 0 }
            .thenBy { if (it.aiTranslated) 1 else 0 }
            .thenBy { fpsRank(it.fps, stream) }
            .thenByDescending { it.fromTrusted }
            .thenByDescending { it.downloads },
    )
}

/**
 * 0 = cadence identique, 1 = corrigeable, 2 = inconnue.
 *
 * Une cadence différente mais *connue* passe devant une cadence absente : elle
 * se corrige exactement, alors qu'un sous-titre qui ne déclare rien laisse
 * l'utilisateur régler à la main.
 */
private fun fpsRank(subtitleFps: Double?, streamFps: Double?): Int {
    val sub = normalizeFps(subtitleFps) ?: return 2
    if (streamFps == null) return 1
    return if (abs(sub - streamFps) < 0.01) 0 else 1
}

/** Minuscules, lettres et chiffres seulement, un espace entre les mots : « Body.of.the.Sanctioned » et « Body of the Sanctioned » se rejoignent. */
private fun normalizeTitle(text: String): String =
    text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex("\\s+"), " ")
