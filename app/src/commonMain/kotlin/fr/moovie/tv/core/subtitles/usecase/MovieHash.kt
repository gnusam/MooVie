package fr.moovie.tv.core.subtitles.usecase

/** Ce que l'empreinte lit en tête et en queue de fichier : 64 Kio chacun. */
const val HASH_CHUNK = 65_536

/**
 * L'empreinte OpenSubtitles d'un fichier vidéo (`moviehash`).
 *
 * Algorithme public : la taille du fichier, à laquelle on ajoute, modulo 2^64,
 * chaque mot de 64 bits petit-boutiste des 64 premiers puis des 64 derniers
 * kilo-octets. Rendue en seize chiffres hexadécimaux, zéros de tête compris.
 *
 * Null si l'une des deux plages n'a pas exactement [HASH_CHUNK] octets : un
 * fichier plus court que 128 Kio n'est pas une vidéo, et une plage tronquée
 * donnerait une empreinte fausse, pire qu'aucune.
 */
fun openSubtitlesHash(sizeBytes: Long, head: ByteArray, tail: ByteArray): String? {
    if (head.size != HASH_CHUNK || tail.size != HASH_CHUNK || sizeBytes < 2L * HASH_CHUNK) return null
    var hash = sizeBytes
    hash += somme(head)
    hash += somme(tail)
    return hash.toULong().toString(16).padStart(16, '0')
}

private fun somme(octets: ByteArray): Long {
    var total = 0L
    var i = 0
    while (i + 8 <= octets.size) {
        var mot = 0L
        for (k in 7 downTo 0) {
            mot = (mot shl 8) or (octets[i + k].toLong() and 0xFF)
        }
        total += mot
        i += 8
    }
    return total
}

/**
 * Le groupe (ou tag) de release d'un nom de fichier : « FORSEE » dans
 * `The.Cook.of.Castamar.S01E01.720p.WEB.H264-FORSEE.mkv`, « Erai-raws » dans
 * `[Erai-raws] One Piece - 01 [1080p].mkv`. Null si rien ne ressemble à un tag.
 *
 * Deux fichiers du même groupe sont le même montage : un sous-titre déposé
 * pour l'un est calé pour l'autre. C'est l'indice le plus fort après
 * l'empreinte elle-même.
 */
fun releaseGroup(filename: String?): String? {
    val nom = filename?.substringAfterLast('/')?.substringBeforeLast('.')?.trim() ?: return null
    if (nom.isEmpty()) return null
    Regex("""^\[([^\]]{2,24})]""").find(nom)?.groupValues?.get(1)?.let { return it.lowercase() }
    val queue = nom.substringAfterLast('-', "").trim()
        .substringBefore('[').substringBefore('(').trim()
    if (queue.length !in 3..24 || !queue.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return null
    if (queue.all { it.isDigit() }) return null
    return queue.lowercase()
}

/** Vrai quand la release du candidat porte le groupe du fichier joué. */
fun sameReleaseGroup(candidateRelease: String, group: String?): Boolean {
    if (group == null || group.length < 3) return false
    return candidateRelease.lowercase().contains(group)
}
