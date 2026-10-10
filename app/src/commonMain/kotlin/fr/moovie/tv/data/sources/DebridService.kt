package fr.moovie.tv.data.sources

/**
 * Le debrid qui sert les fichiers de l'addon Stremio.
 *
 * Torrentio sait parler à plusieurs : la clé du service voyage dans l'adresse
 * de l'addon (`realdebrid=…`, `torbox=…`), et c'est tout ce qui change d'un
 * service à l'autre — les marqueurs `[RD+]`, `[TB+]` sont déjà lus, l'identité
 * d'une source est son infohash, les cartons sont ceux de Torrentio.
 *
 * Pourquoi un choix et pas une constante : depuis mai 2026 Real-Debrid filtre
 * par mots-clés de nom de release (le « failed_infringement » de Castamar), et
 * les autres services n'ont pas cette règle. Sam, 10/10/2026 : « mets-le en
 * option dans le paramètre de l'application, comme ça on pourra switcher le
 * jour où on aura envie ». Un service sans clé dans la configuration reste
 * proposé, grisé : on voit qu'il existe et ce qu'il manque.
 *
 * @property parametre le nom du paramètre dans l'adresse de l'addon.
 * @property nom le nom affiché, tel que le service l'écrit.
 */
enum class DebridService(val parametre: String, val nom: String) {
    REALDEBRID("realdebrid", "Real-Debrid"),
    TORBOX("torbox", "TorBox"),
}
