package fr.moovie.tv.core.player

/**
 * Les langues audio à préférer au démarrage d'un flux, codes ISO 639-1.
 *
 * Une source étiquetée VO n'est pas forcément mono-piste : chez les catalogues
 * Stremio, l'animation japonaise arrive presque toujours en « Dual Audio »
 * (doublage anglais + japonais dans le même fichier), et c'est le **doublage
 * anglais** que l'encodeur marque par défaut. Sans préférence, le lecteur le
 * retient, et la VO choisie sur la fiche se joue en anglais.
 *
 *  - VF : le français ;
 *  - VO et VOSTFR : la langue originale annoncée par TMDB ;
 *  - langue inconnue, ou VO sans langue originale : rien, le lecteur garde la
 *    piste par défaut du fichier — imposer une langue au hasard serait pire.
 *
 * Le choix fait à la main dans le menu « Audio » reste prioritaire : il est
 * mémorisé par titre et réappliqué par-dessus.
 */
fun preferredAudioLanguages(streamLanguage: String, originalLanguage: String): List<String> {
    val original = originalLanguage.trim().lowercase()
    return when (streamLanguage.trim().uppercase()) {
        "VF" -> listOf("fr")
        "VO", "VOSTFR" -> if (original.isNotEmpty() && original != "xx") listOf(original) else emptyList()
        else -> emptyList()
    }
}

/**
 * La ligne « d'où ça vient » sous le titre du lecteur.
 *
 * Le fork range le debrid Stremio parmi les catalogues de streaming, et rien
 * à l'écran ne disait lequel des deux jouait : on lançait « la version
 * Stremio » sans jamais pouvoir le vérifier. Elle dit aussi la langue de la
 * source et la piste audio réellement jouée — une VO dont l'audio est en
 * anglais se voit alors tout de suite.
 *
 * @param hoster l'hébergeur du lien (`stremio` pour le debrid), vide pour un
 *   fichier local.
 * @param audio le libellé de la piste audio active, vide si inconnue.
 */
fun playerSourceLine(hoster: String, language: String, audio: String): String = buildList {
    add(
        when {
            hoster.equals("stremio", ignoreCase = true) -> "Stremio (debrid)"
            hoster.isBlank() -> "Fichier téléchargé"
            else -> "Streaming · $hoster"
        },
    )
    language.takeIf { it.isNotBlank() }?.let(::add)
    audio.takeIf { it.isNotBlank() }?.let { add("audio : $it") }
}.joinToString(" · ")
