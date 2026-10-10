package fr.moovie.tv.ui.details

import fr.moovie.tv.core.sources.model.EmbedLink

/**
 * Le catalogue de tête passe avant la vitesse de réponse.
 *
 * La lecture rapide prenait le **premier lien arrivé** dans la langue voulue.
 * Mesuré le 10/10/2026 sur One Piece : vidapi répond en 0,2 s, stremio en
 * 4,9 s (286 flux à analyser) ; la lecture partait sur vidapi à 2,9 s, en
 * doublage anglais étiqueté VO, alors que l'ordre des catalogues met stremio
 * en tête pour de bonnes raisons — un fichier chez le debrid, les pistes
 * d'origine, pas d'hébergeur gratuit. Sam : « nous, ce qu'on veut en priorité
 * c'est prendre Stremio parce que c'est mieux ».
 *
 * Règle : tant que le catalogue de tête (le premier de l'ordre de l'utilisateur)
 * n'a pas répondu, un lien d'un autre catalogue attend — au plus
 * [PATIENCE_TOURS] tours de boucle. Au-delà, ou dès que la tête a répondu
 * (liens, vide ou échec), la cascade reprend son cours normal. Rien ne change
 * quand les sources viennent du cache : tout y est déjà « répondu ».
 *
 * Seule la tête est attendue, pas chaque catalogue mieux classé que le lien
 * candidat : swiftflow, deuxième de l'ordre par défaut, expire à 12 s une fois
 * sur deux, et l'attendre aurait rendu chaque lecture plus lente qu'avant.
 */
fun catalogueDeTeteAttendu(
    providers: List<ProviderProgress>,
    candidat: EmbedLink,
    toursAttendus: Int,
): Boolean {
    val tete = providers.firstOrNull() ?: return false
    if (tete.status != ProviderStatus.LOADING) return false
    if (candidat.provider == tete.name) return false
    return toursAttendus < PATIENCE_TOURS
}

/**
 * La source mémorisée ne vaut que si elle vient du catalogue de tête.
 *
 * Elle est retenue par la lecture rapide elle-même : avant la règle ci-dessus,
 * c'était le lien le plus prompt, pas le plus voulu, et le rejouer en priorité
 * aurait figé ce choix pour toutes les reprises. Un souvenir d'un autre
 * catalogue est donc ignoré — pas oublié : si la tête n'a rien, la cascade le
 * retrouvera et le remémorisera. Sans catalogue de tête connu (réglages
 * illisibles), on fait confiance au souvenir.
 */
fun souvenirPrioritaire(souvenir: EmbedLink, catalogueDeTete: String?): Boolean =
    catalogueDeTete == null || souvenir.provider == catalogueDeTete

/**
 * Tours de 250 ms : six secondes. Au-dessus du temps mesuré pour stremio sur
 * une série à 286 flux (4,9 s), en dessous du délai d'un catalogue (12 s) — on
 * ne veut pas que la tête en panne coûte son délai entier à chaque lecture.
 */
const val PATIENCE_TOURS = 24
