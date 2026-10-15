package fr.moovie.tv.core.subtitles.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import fr.moovie.tv.core.subtitles.port.SubtitleTranslator

/**
 * Traduit un fichier de sous-titres réplique par réplique, **en publiant au fil
 * de l'eau**.
 *
 * ## Pourquoi progressif
 *
 * Un film de deux heures porte autour de mille répliques. Les traduire toutes
 * avant d'afficher quoi que ce soit imposerait une attente d'une minute ou deux
 * devant un écran qui ne dit rien. Or la lecture commence par le début : les
 * premières répliques sont les seules dont on ait besoin tout de suite.
 *
 * On avance donc par lots dans l'ordre du film, et [onProgress] reçoit après
 * chaque lot un fichier **complet et valide** — les répliques pas encore
 * traduites y figurent dans leur langue d'origine. Le lecteur peut le recharger
 * à tout moment sans jamais perdre une ligne ni une seconde de synchronisation.
 * Mesuré côté terminal sur un montage comparable : la traduction va environ
 * quinze fois plus vite que la lecture ne consomme les répliques, donc elle
 * reste devant sans qu'on ait à l'attendre.
 *
 * ## Pourquoi on ne perd jamais une réplique
 *
 * Un lot dont la réponse ne correspond pas est **coupé en deux et repris**,
 * jusqu'à la réplique isolée. Constaté sur le script terminal : un lot de vingt
 * revenait à dix-neuf, et sans cette vérification tout le reste du fichier se
 * décalait d'un cran — chaque phrase affichée sur le plan suivant, jusqu'à la
 * fin du film. Un décalage d'un cran ne se voit pas tout de suite, et c'est ce
 * qui le rend redoutable.
 *
 * Au bout de la récursion, une réplique qui résiste garde son texte d'origine.
 * Une ligne en anglais au milieu du français est un défaut visible et local ;
 * un fichier décalé est un défaut invisible et total.
 */
suspend fun translateCues(
    cues: List<SrtCue>,
    translator: SubtitleTranslator,
    batchSize: Int = BATCH_PAR_DEFAUT,
    parallelism: Int = 1,
    onProgress: suspend (cues: List<SrtCue>, done: Int) -> Unit = { _, _ -> },
): List<SrtCue> {
    if (cues.isEmpty()) return cues
    val taille = batchSize.coerceAtLeast(1)
    val sortie = cues.toMutableList()
    val bornes = (cues.indices step taille).map { it to minOf(it + taille, cues.size) }

    // Plusieurs lots en vol : un lot attend surtout la réponse du modèle, pas
    // la machine. Lancés dans l'ordre du film, les premiers finissent en
    // premier ; chaque lot fini publie quand même un fichier entier, et
    // `done` compte les répliques faites, quel que soit l'ordre d'arrivée.
    val verrou = Mutex()
    val permis = Semaphore(parallelism.coerceAtLeast(1))
    var faites = 0
    coroutineScope {
        bornes.map { (debut, fin) ->
            async {
                val traduits = permis.withPermit {
                    translateBatch(cues.subList(debut, fin).map { it.text }, translator)
                }
                verrou.withLock {
                    for ((j, texte) in traduits.withIndex()) {
                        sortie[debut + j] = sortie[debut + j].copy(lines = texte.split('\n'))
                    }
                    faites += fin - debut
                    onProgress(sortie.toList(), faites)
                }
            }
        }.awaitAll()
    }
    return sortie
}

/**
 * Un lot, avec repli par bissection. Rend **toujours** autant de textes que
 * reçus : ceux qui n'ont pas abouti sont rendus inchangés.
 */
private suspend fun translateBatch(
    textes: List<String>,
    translator: SubtitleTranslator,
): List<String> {
    if (textes.isEmpty()) return textes

    // La vérification de taille est le contrat, et elle est faite **ici** et non
    // chez l'adaptateur : c'est le domaine qui sait pourquoi elle compte.
    val rendu = runCatching { translator.translate(textes) }.getOrNull()
    if (rendu != null && rendu.size == textes.size && !perdUneReplique(textes, rendu)) return rendu

    if (textes.size == 1) return textes

    val milieu = textes.size / 2
    return translateBatch(textes.take(milieu), translator) +
        translateBatch(textes.drop(milieu), translator)
}

/**
 * Une traduction qui rend **vide** une réplique qui ne l'était pas est un échec,
 * même au bon compte.
 *
 * Constaté le 15/10/2026 sur Fullmetal Alchemist, épisode 13 : un lot de
 * quarante est revenu avec le bon nombre de clés, mais trente-neuf chaînes
 * vides. La sortie structurée garantit les clés, pas leur contenu. Le lot
 * passait le contrôle de taille, et trois minutes et demie de film sont restées
 * sans le moindre sous-titre. Traité comme un lot raté, il repasse par la
 * bissection, et une réplique qui revient vide garde son texte d'origine.
 */
private fun perdUneReplique(source: List<String>, traduit: List<String>): Boolean =
    source.indices.any { source[it].isNotBlank() && traduit[it].isBlank() }

/**
 * Une traduction déjà rangée sur disque est-elle réutilisable telle quelle ?
 *
 * Non si elle n'a pas le même nombre de répliques que l'original, ou si une
 * réplique non vide y est devenue vide : c'est la trace d'un lot raté (voir
 * [perdUneReplique]), écrit avant que ce contrôle n'existe. Elle se refait
 * alors, au lieu d'être resservie à chaque reprise.
 */
fun traductionComplete(original: List<SrtCue>, traduit: List<SrtCue>): Boolean =
    original.size == traduit.size && !perdUneReplique(original.map { it.text }, traduit.map { it.text })

/**
 * Quarante répliques, et le choix vient d'une mesure : le débit ne dépend pas de
 * la taille du lot, on est limité par le modèle et non par le coût de la requête.
 * Un lot plus petit n'est donc pas plus lent, et il a deux avantages — la
 * première ligne de français arrive plus tôt, et un échec coûte moins cher à
 * reprendre par bissection.
 */
const val BATCH_PAR_DEFAUT = 40
