package fr.moovie.tv.core.subtitles

import fr.moovie.tv.core.subtitles.port.SubtitleTranslator
import fr.moovie.tv.core.subtitles.usecase.parseSrt
import fr.moovie.tv.core.subtitles.usecase.renderSrt
import fr.moovie.tv.core.subtitles.usecase.traductionComplete
import fr.moovie.tv.core.subtitles.usecase.translateCues
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Traduction des répliques — fonctions pures, aucun réseau.
 *
 * Ce qui est éprouvé ici n'est pas la qualité du français, qu'aucun test ne
 * saurait juger, mais la seule propriété qui puisse casser un film en silence :
 * **une réplique traduite reste sur son horodatage**. Un décalage d'un cran ne
 * se voit pas sur la première phrase et fausse tout le reste.
 */
class TranslateCuesTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:02,000
        Hello there.

        2
        00:00:03,000 --> 00:00:04,000
        How are you?

        3
        00:00:05,000 --> 00:00:06,000
        Fine, thanks.
        And you?
    """.trimIndent()

    /** Traducteur fidèle : il préfixe, ce qui suffit à voir qui a bougé. */
    private val fidele = SubtitleTranslator { textes -> textes.map { "[fr] $it" } }

    @Test
    fun `un SRT se relit et se réécrit sans rien perdre`() {
        val cues = parseSrt(srt)
        assertEquals(3, cues.size)
        assertEquals("00:00:03,000 --> 00:00:04,000", cues[1].stamps)
        assertEquals(listOf("Fine, thanks.", "And you?"), cues[2].lines)

        // Le fichier rendu se termine par un saut de ligne, ce que `trimIndent`
        // retire du litteral : un SRT en porte un, et certains lecteurs ignorent
        // la derniere replique quand il manque.
        assertEquals(srt + "\n", renderSrt(cues))
    }

    @Test
    fun `relire ce qu'on vient d'ecrire ne change plus rien`() {
        // Point fixe : c'est ce qui autorise a republier le fichier a chaque
        // lot sans qu'il derive d'une etape a l'autre.
        val unTour = renderSrt(parseSrt(srt))
        assertEquals(unTour, renderSrt(parseSrt(unTour)))
    }

    @Test
    fun `les retours chariot Windows et les numéros absents passent`() {
        val brut = "00:00:01,000 --> 00:00:02,000\r\nSalut\r\n\r\n\r\n" +
            "7\r\n00:00:03,000 --> 00:00:04,000\r\nToi\r\n"
        val cues = parseSrt(brut)
        assertEquals(2, cues.size)
        // Renumérote : le numéro du fichier ne sert pas d'appui, certains
        // fichiers les repetent ou les sautent.
        assertEquals(listOf(1, 2), cues.map { it.number })
    }

    @Test
    fun `un bloc sans horodatage est ecarte au lieu de tout decaler`() {
        val avecEnTete = "Sous-titres par Machin\n\n" + srt
        assertEquals(3, parseSrt(avecEnTete).size)
    }

    @Test
    fun `les horodatages ne bougent jamais`() = runBlocking {
        val avant = parseSrt(srt)
        val apres = translateCues(avant, fidele, batchSize = 2)
        assertEquals(avant.map { it.stamps }, apres.map { it.stamps })
        assertEquals("[fr] Hello there.", apres[0].text)
    }

    @Test
    fun `un lot au mauvais compte est coupe en deux, pas accepte`() = runBlocking {
        // Le piège mesuré : un lot de n revient a n-1. L'accepter decalerait
        // chaque replique suivante d'un cran jusqu'a la fin du film.
        var lotsVus = 0
        val tronque = SubtitleTranslator { textes ->
            lotsVus++
            if (textes.size > 1) textes.drop(1).map { "[fr] $it" } else listOf("[fr] ${textes[0]}")
        }
        val apres = translateCues(parseSrt(srt), tronque, batchSize = 3)
        assertEquals(listOf("[fr] Hello there.", "[fr] How are you?"), apres.take(2).map { it.text })
        assertTrue(lotsVus > 1, "le lot fautif aurait du etre redecoupe")
    }

    @Test
    fun `une replique qui resiste garde son texte plutot que de disparaitre`() = runBlocking {
        // Une ligne en anglais au milieu du francais est un defaut visible et
        // local ; une replique manquante decale tout ce qui suit.
        val capricieux = SubtitleTranslator { textes ->
            if (textes.any { "How" in it }) null else textes.map { "[fr] $it" }
        }
        val apres = translateCues(parseSrt(srt), capricieux, batchSize = 3)
        assertEquals(3, apres.size)
        assertEquals("How are you?", apres[1].text)
        assertEquals("[fr] Hello there.", apres[0].text)
        assertEquals("[fr] Fine, thanks.\nAnd you?", apres[2].text)
    }

    @Test
    fun `un traducteur qui leve ne fait pas tomber la traduction`() = runBlocking {
        val explosif = SubtitleTranslator { error("panne") }
        val apres = translateCues(parseSrt(srt), explosif, batchSize = 2)
        assertEquals(parseSrt(srt).map { it.text }, apres.map { it.text })
    }

    @Test
    fun `chaque etape publie un fichier complet et jouable`() = runBlocking {
        // C'est ce qui autorise le lecteur a recharger a tout moment : jamais
        // une liste partielle, seulement des repliques pas encore traduites.
        val etapes = mutableListOf<Int>()
        translateCues(parseSrt(srt), fidele, batchSize = 1) { cues, faites ->
            assertEquals(3, cues.size, "le fichier publie doit rester entier")
            etapes += faites
        }
        assertEquals(listOf(1, 2, 3), etapes)
    }

    @Test
    fun `des lots en parallele rendent le meme fichier, dans l'ordre`() = runBlocking {
        val sequentiel = translateCues(parseSrt(srt), fidele, batchSize = 1)
        val etapes = mutableListOf<Int>()
        val parallele = translateCues(parseSrt(srt), fidele, batchSize = 1, parallelism = 3) { cues, faites ->
            assertEquals(3, cues.size, "le fichier publie doit rester entier")
            etapes += faites
        }
        assertEquals(sequentiel.map { it.text }, parallele.map { it.text })
        assertEquals(sequentiel.map { it.stamps }, parallele.map { it.stamps })
        assertEquals(listOf(1, 2, 3), etapes)
    }
    @Test
    fun `un lot au bon compte mais aux repliques vides est refait, pas accepte`() = runBlocking {
        // Constate sur Fullmetal Alchemist 13 : quarante cles, trente-neuf
        // chaines vides, et trois minutes et demie sans sous-titres.
        val videur = SubtitleTranslator { textes ->
            if (textes.size > 1) textes.map { "" } else listOf("[fr] ${textes[0]}")
        }
        val apres = translateCues(parseSrt(srt), videur, batchSize = 3)
        assertEquals(parseSrt(srt).map { "[fr] ${it.text}" }, apres.map { it.text })
    }

    @Test
    fun `une replique qui revient toujours vide garde son texte`() = runBlocking {
        val muet = SubtitleTranslator { textes -> textes.map { "" } }
        val apres = translateCues(parseSrt(srt), muet, batchSize = 3)
        assertEquals(parseSrt(srt).map { it.text }, apres.map { it.text })
    }

    @Test
    fun `une traduction trouee sur disque n'est pas reutilisee`() {
        val original = parseSrt(srt)
        val bonne = original.map { it.copy(lines = listOf("[fr] ${it.text}")) }
        val trouee = bonne.mapIndexed { i, c -> if (i == 1) c.copy(lines = listOf("")) else c }
        assertTrue(traductionComplete(original, bonne))
        assertFalse(traductionComplete(original, trouee))
        assertFalse(traductionComplete(original, bonne.dropLast(1)))
        // Relue depuis le fichier, la replique vide disparait au parse : c'est
        // le compte qui la trahit.
        assertFalse(traductionComplete(original, parseSrt(renderSrt(trouee))))
    }
}
