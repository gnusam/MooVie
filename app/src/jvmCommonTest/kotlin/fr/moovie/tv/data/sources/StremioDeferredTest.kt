package fr.moovie.tv.data.sources

import fr.moovie.tv.core.sources.port.HttpGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Les flux pas encore chez le debrid, et la langue que les releases annoncent.
 *
 * Le corps reprend la réponse de Torrentio pour *La cocinera de Castamar*
 * S1E1 (10/10/2026) : un seul `[RD+]`, un « MultiLang » sans piste espagnole,
 * et des versions espagnoles, italienne, russe à télécharger.
 */
class StremioDeferredTest {

    private val jamais = HttpGateway { null }

    private val castamar = """
        {"streams":[
          {"name":"[RD+] Torrentio\n1080p",
           "title":"The.Cook.of.Castamar.S01.Complete.1080P.WebDL.x264.DDP5.1.MultiLang.WildBrian\nS01E01_The Essential Ingredient.mkv\n👤 0 💾 1.5 GB ⚙️ TorrentGalaxy",
           "url":"https://torrentio.strem.fun/resolve/realdebrid/CLE/aed1ca732ef90f09bdaa88520534ad6f6ff7d61d/null/11/S01E01.mkv",
           "behaviorHints":{"filename":"S01E01_The Essential Ingredient.mkv"}},
          {"name":"[RD download] Torrentio\nWEBRip",
           "title":"La Cuoca di Castamar - Stagione 1 (2021) [COMPLETA] SD x264 AAC ITA SUB ITA\nLa.cuoca.di.Castamar.01x01.ITA.WEBRIP.x264-mkeagle3.mkv\n👤 0 💾 380 MB ⚙️ ilCorsaroNero",
           "url":"https://torrentio.strem.fun/resolve/realdebrid/CLE/aa8329f5f9106287964ed8616aa08306c3f37d37/null/0/ita.mkv",
           "behaviorHints":{"filename":"La.cuoca.di.Castamar.01x01.ITA.WEBRIP.x264-mkeagle3.mkv"}},
          {"name":"[RD download] Torrentio\n720p",
           "title":"Кухарка из Кастамара / The Cook of Castamar / Сезон: 1 / Серии: 1-12 из 12 [2021 WEB-DL 720p] Original + RUS\n👤 0 💾 11 GB ⚙️ Rutor",
           "url":"https://torrentio.strem.fun/resolve/realdebrid/CLE/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/null/0/rus.mkv"},
          {"name":"[RD download] Torrentio\nWEBRip",
           "title":"The.Cook.of.Castamar.S01.SPANISH.WEBRip.x264-ION10\nThe.Cook.of.Castamar.S01E01.WEBRip.x264-ION10.mp4\n👤 0 💾 661.36 MB ⚙️ EZTV",
           "url":"https://torrentio.strem.fun/resolve/realdebrid/CLE/cccccccccccccccccccccccccccccccccccccccc/null/0/es.mp4",
           "behaviorHints":{"filename":"The.Cook.of.Castamar.S01E01.WEBRip.x264-ION10.mp4"}},
          {"name":"[RD download] Torrentio\n720p",
           "title":"The.Cook.of.Castamar.S01.SPANISH.720p.NF.WEBRip.DDP5.1.x264-FORSEE\nThe.Cook.of.Castamar.S01E01.720p.WEB.H264-FORSEE.mkv\n👤 0 💾 1.4 GB ⚙️ RARBG",
           "url":"https://torrentio.strem.fun/resolve/realdebrid/CLE/dddddddddddddddddddddddddddddddddddddddd/null/0/es720.mkv",
           "behaviorHints":{"filename":"The.Cook.of.Castamar.S01E01.720p.WEB.H264-FORSEE.mkv"}}
        ]}
    """.trimIndent()

    private fun liens(originalLanguage: String = "es") =
        StremioProvider(jamais, cachedOnly = false).parse(castamar, StremioProvider.CIBLE_EPISODE, originalLanguage)

    @Test
    fun `ce qui joue tout de suite passe devant ce que le debrid doit aller chercher`() {
        val l = liens()
        assertTrue(l.first().url.contains("aed1ca73"), "le [RD+] d'abord")
        assertFalse(l.first().deferred)
        assertTrue(l.drop(1).all { it.deferred }, "tous les autres sont différés")
    }

    @Test
    fun `un lien differe le dit sur la fiche`() {
        val es = liens().first { it.url.contains("cccccccc") }
        assertTrue(es.variant.orEmpty().contains("à récupérer"), es.variant.orEmpty())
        assertTrue(es.variant.orEmpty().contains("espagnol"), es.variant.orEmpty())
        assertEquals("VO", es.language)
    }

    @Test
    fun `les doublages etrangers n'ont rien a faire sur une fiche espagnole`() {
        val urls = liens().map { it.url }
        assertTrue(urls.none { it.contains("aa8329f5") }, "le doublage italien est écarté")
        assertTrue(urls.none { it.contains("bbbbbbbb") }, "le rip russe est écarté")
        assertEquals(3, urls.size)
    }

    @Test
    fun `sans langue d'origine connue, on ne juge pas`() {
        assertEquals(5, liens(originalLanguage = "").size)
    }

    @Test
    fun `le 720p espagnol passe avant le SD espagnol, les deux derriere le cache`() {
        val differes = liens().filter { it.deferred }.map { it.url }
        assertTrue(differes.first().contains("dddddddd"), "720p en tête des différés : $differes")
    }

    @Test
    fun `la langue annoncee se lit dans le nom, pas dans les marqueurs de sous-titres`() {
        assertEquals("es" to "espagnol", StremioProvider.langueAnnoncee("The.Cook.of.Castamar.S01.SPANISH.WEBRip"))
        assertEquals("es" to "espagnol", StremioProvider.langueAnnoncee("La cocinera de Castamar [HDTV][Castellano]"))
        assertEquals("ru" to "russe", StremioProvider.langueAnnoncee("Кухарка из Кастамара WEB-DL 720p"))
        assertNull(StremioProvider.langueAnnoncee("The.Cook.Of.Castamar.S01.SweSub-EngSub.720p.x264"))
        assertNull(StremioProvider.langueAnnoncee("The.Cook.of.Castamar.S01.1080P.MultiLang.WildBrian"))
    }

    @Test
    fun `un MULTi italien n'est pas un doublage etranger, il porte aussi l'original`() {
        val release = "Dune.2021.MULTi.ITA.ENG.1080p.BluRay"
        assertFalse(StremioProvider.doublageEtranger(StremioProvider.langueAnnoncee(release), release, "en"))
    }
}
