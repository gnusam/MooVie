package fr.moovie.tv.core.sources.usecase

/**
 * Ce qui identifie une **release** à travers ses liens.
 *
 * Un lien d'addon Stremio porte l'infohash du torrent
 * (`…/resolve/realdebrid/<clé>/<infohash>/…`) : c'est lui qui désigne le
 * fichier, et il est le même pour tous les épisodes d'un pack de saison. Une
 * mémoire posée sur l'URL complète ne vaudrait que pour un épisode ; posée sur
 * l'infohash, elle vaut pour la saison — exactement ce qu'on veut quand on a
 * appris qu'un rip « MultiLang » n'a pas de piste dans la langue d'origine.
 *
 * Pour tout autre lien, l'URL elle-même.
 */
fun sourceIdentity(url: String): String =
    INFOHASH.find(url)?.groupValues?.get(1)?.lowercase() ?: url

private val INFOHASH = Regex("""/([0-9a-fA-F]{40})(?:/|$)""")
