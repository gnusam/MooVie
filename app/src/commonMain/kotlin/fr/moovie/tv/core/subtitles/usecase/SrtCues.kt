package fr.moovie.tv.core.subtitles.usecase

/**
 * Une réplique de sous-titre : son numéro, ses horodatages, son texte.
 *
 * [stamps] n'est pas analysé, seulement transporté. La traduction ne touche
 * jamais au temps — c'est précisément ce qui la rend sûre : le fichier produit
 * est synchrone avec l'original **par construction**, sans estimation ni
 * recalage. Voir `retimeSrt` pour ce qui, lui, manipule les horodatages.
 */
data class SrtCue(
    val number: Int,
    val stamps: String,
    val lines: List<String>,
) {
    val text: String get() = lines.joinToString("\n")
}

/**
 * Découpe un SRT en répliques.
 *
 * Tolérant par choix : ces fichiers viennent de contributeurs, pas d'un
 * générateur. On accepte les numéros absents ou dans le désordre, les blocs
 * séparés par plusieurs lignes vides, et le retour chariot de Windows. Un bloc
 * sans ligne d'horodatage — commentaire d'en-tête, ligne parasite — est écarté
 * plutôt que de décaler tout le reste.
 */
fun parseSrt(text: String): List<SrtCue> {
    val out = mutableListOf<SrtCue>()
    var numero = 0
    for (bloc in text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))) {
        val lignes = bloc.split('\n').filter { it.isNotBlank() }
        if (lignes.isEmpty()) continue
        val iStamp = lignes.indexOfFirst { FLECHE in it }
        if (iStamp == -1) continue
        // Le numéro du fichier est ignoré au profit d'un compteur : certains
        // fichiers les répètent ou les sautent, et c'est sur ce numéro que
        // repose l'appariement de la traduction.
        numero++
        out += SrtCue(
            number = numero,
            stamps = lignes[iStamp].trim(),
            lines = lignes.drop(iStamp + 1),
        )
    }
    return out
}

/** Réécrit un SRT complet, numéros renumérotés depuis 1. */
fun renderSrt(cues: List<SrtCue>): String = buildString {
    cues.forEachIndexed { i, cue ->
        append(i + 1).append('\n')
        append(cue.stamps).append('\n')
        append(cue.text).append('\n')
        if (i != cues.lastIndex) append('\n')
    }
}

private const val FLECHE = "-->"
