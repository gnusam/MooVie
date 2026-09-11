package fr.moovie.tv.core.subtitles.port

/**
 * Traduit un lot de répliques, **une pour une**.
 *
 * C'est un port, comme `SubtitleCatalog` : le domaine ignore si l'implémentation
 * appelle un modèle distant, un service de traduction ou un dictionnaire. Ce
 * qu'il exige tient en une ligne — autant de textes rendus que reçus, dans le
 * même ordre.
 *
 * ## La contrainte qui gouverne tout le reste
 *
 * Chaque texte est un sous-titre affiché à un instant précis. Un traducteur qui
 * fusionne deux répliques voisines, ou qui déplace la fin d'une phrase vers la
 * suivante, produit un français plus naturel **et un fichier faux** : le
 * spectateur lit à l'écran une phrase qui appartient au plan d'avant.
 *
 * C'est un piège mesuré, pas une crainte théorique : un prompt « qualité »
 * améliore nettement la langue et réorganise le texte entre répliques voisines.
 * L'implémentation doit donc l'interdire explicitement, et le domaine doit
 * **vérifier** plutôt que faire confiance — d'où le contrat de taille, et le
 * `null` qui dit « je n'ai pas su », jamais un lot tronqué.
 */
fun interface SubtitleTranslator {

    /**
     * Rend les traductions de [textes], dans l'ordre et en même nombre.
     *
     * Null quand le lot n'a pas abouti : réseau, refus, ou réponse dont le
     * compte ne correspond pas. L'appelant décide alors quoi faire — couper le
     * lot en deux et réessayer, ou abandonner ce morceau. Ne doit pas lever.
     */
    suspend fun translate(textes: List<String>): List<String>?
}
