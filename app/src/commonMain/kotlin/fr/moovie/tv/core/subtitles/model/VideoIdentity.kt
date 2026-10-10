package fr.moovie.tv.core.subtitles.model

/**
 * L'empreinte du **fichier** vidéo joué, telle qu'OpenSubtitles l'entend.
 *
 * C'est le concept que Sam réclamait le 10/10/2026 : « il n'y a pas un concept
 * dans les sous-titres qui permet d'identifier le média efficacement avec le
 * checksum du média ? ». Il existe : le `moviehash`, calculé sur la taille et
 * les 64 premiers et 64 derniers kilo-octets du fichier. Un sous-titre déposé
 * avec cette empreinte a été calé sur **ce** fichier — pas sur une autre
 * release, un autre montage, une autre cadence.
 *
 * Calculable sur un fichier direct (debrid, téléchargement local), pas sur un
 * flux segmenté : voir `videoIdentity`.
 */
data class VideoIdentity(val hash: String, val sizeBytes: Long)
