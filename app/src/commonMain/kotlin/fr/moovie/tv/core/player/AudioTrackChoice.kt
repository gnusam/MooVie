package fr.moovie.tv.core.player

import fr.moovie.tv.ui.player.PlayerTrack

/**
 * La piste audio à jouer quand personne n'a encore choisi.
 *
 * Mesuré le 10/10/2026 sur *La cocinera de Castamar* (rip « MultiLang ») : le
 * fichier portait six pistes — allemand, anglais britannique, français, turc,
 * une 5.1 sans titre marquée `en`, et « European Spanish - Audio Description ».
 * La préférence de langue (`es`, langue originale) ne voyait qu'une seule piste
 * espagnole et la prenait : la série se jouait en audiodescription. Sam :
 * « l'audio description, j'en veux pas, jamais ».
 *
 * La règle, dans l'ordre :
 *
 *  1. jamais une piste non décodable, jamais une audiodescription ni un
 *     commentaire — quoi qu'en dise la langue ;
 *  2. la première langue voulue qui a une piste ordinaire, la mieux dotée en
 *     canaux (une 5.1 devant une 2.0, la première du fichier à égalité) ;
 *  3. sinon, le choix de l'encodeur : la piste marquée par défaut, puis la plus
 *     riche en canaux. C'est ce que ferait le lecteur seul, l'audiodescription
 *     en moins.
 *
 * Rend null quand rien n'est choisissable (fichier mono-piste, tout en
 * audiodescription, lecteur qui ne décrit pas ses pistes) : on ne touche alors
 * à rien.
 *
 * Le choix fait à la main dans le menu reste prioritaire et n'arrive pas ici —
 * voir `matchAudioTrack`, qui le rejoue par libellé.
 */
fun chooseAudioTrack(tracks: List<PlayerTrack>, preferred: List<String>): PlayerTrack? {
    val candidates = tracks.filter { it.supported && !it.audioDescription }
    if (candidates.isEmpty()) return null

    for (wanted in preferred) {
        val base = languageBase(wanted) ?: continue
        val matches = candidates.filter { languageBase(it.language) == base }
        if (matches.isNotEmpty()) return matches.maxBy { it.channels }
    }

    val defaults = candidates.filter { it.isDefault }
    if (defaults.isNotEmpty()) return defaults.maxBy { it.channels }
    return candidates.maxBy { it.channels }
}

/**
 * Vrai quand le fichier joué n'a **aucune** piste ordinaire dans la langue
 * d'origine : la VO n'y est pas, quoi qu'en dise le nom de la release.
 *
 * Prudent par construction : faux dès qu'une piste n'annonce pas sa langue,
 * parce qu'une piste muette est peut-être justement l'originale (mkvmerge
 * marque `eng` ce que l'encodeur n'a pas étiqueté — mesuré sur Castamar, où la
 * 5.1 sans titre s'est avérée en anglais, mais ça ne se généralise pas), et
 * faux sans langue d'origine connue. Une erreur ici écarterait une bonne
 * source pour toute la saison.
 */
fun lacksOriginalAudio(tracks: List<PlayerTrack>, originalLanguage: String): Boolean {
    val original = languageBase(originalLanguage) ?: return false
    if (tracks.isEmpty()) return false
    if (tracks.any { languageBase(it.language) == null }) return false
    return tracks.none { !it.audioDescription && languageBase(it.language) == original }
}

/**
 * Titres qui désignent une audiodescription ou un commentaire, tels qu'on les
 * trouve dans les rips : « Audio Description », « AD », « Audiodescription »,
 * « Descriptive Audio », « Commentary », « Audiodeskription », « VI »
 * (*visually impaired*).
 */
fun isAudioDescription(title: String): Boolean = AUDIO_DESCRIPTION.containsMatchIn(title)

private val AUDIO_DESCRIPTION = Regex(
    """audio[\s_-]*descri|descriptive|audiodeskription|\bAD\b|\bVI\b|visual(ly)?[\s-]*impaired|commentary|commentaire|malvoyant|aveugle""",
    RegexOption.IGNORE_CASE,
)

/**
 * Deux lettres ISO 639-1, région et script écartés : `es-ES`, `spa` et `es`
 * désignent la même langue. Null pour une langue absente ou indéterminée.
 */
private fun languageBase(code: String?): String? {
    val brut = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
    if (brut.isEmpty() || brut == "und" || brut == "xx") return null
    return TROIS_VERS_DEUX[brut] ?: brut.take(2)
}

private val TROIS_VERS_DEUX = mapOf(
    "spa" to "es", "eng" to "en", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de",
    "jpn" to "ja", "ita" to "it", "por" to "pt", "kor" to "ko", "zho" to "zh", "chi" to "zh",
    "rus" to "ru", "tur" to "tr", "nld" to "nl", "dut" to "nl", "pol" to "pl", "swe" to "sv",
    "ara" to "ar", "hin" to "hi", "cat" to "ca", "ell" to "el", "gre" to "el",
)
