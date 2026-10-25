package fr.moovie.tv.data.sources

import fr.moovie.tv.core.sources.port.HttpGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-Debrid et TorBox interrogés ensemble.
 *
 * Le cas d'origine, 25/10/2026 : Torrentio avec la clé TorBox rendait
 * `{"streams":[]}` sur Fullmetal Alchemist, ou le seul carton « TB error »,
 * pendant que Real-Debrid servait 75 flux.
 */
class StremioFusionTest {

    private val provider = StremioProvider(HttpGateway { null }, cachedOnly = false)

    private fun flux(service: String, code: String, hash: String, qualite: String, poids: String, cache: Boolean = true) = """
        {"name":"[$code${if (cache) "+" else " download"}] Torrentio\n$qualite",
         "title":"Fullmetal.Alchemist.Brotherhood.S01E01.$qualite.BluRay.x265\n👤 12 💾 $poids ⚙️ Nyaa",
         "url":"https://torrentio.strem.fun/resolve/$service/CLE/$hash/null/0/fma.mkv",
         "behaviorHints":{"filename":"Fullmetal.Alchemist.Brotherhood.S01E01.$qualite.BluRay.x265.mkv"}}
    """.trimIndent()

    private val realDebrid = """{"streams":[
        ${flux("realdebrid", "RD", "aaaa", "1080p", "1.4 GB")},
        ${flux("realdebrid", "RD", "bbbb", "720p", "700 MB")}
    ]}"""

    private val torBox = """{"streams":[
        ${flux("torbox", "TB", "aaaa", "1080p", "1.4 GB")},
        ${flux("torbox", "TB", "cccc", "1080p", "1.5 GB", cache = false)}
    ]}"""

    private val torBoxEnPanne = """{"streams":[
        {"name":"Torrentio\nTB error","title":"TorBox is currently unavailable!\nPlease try again later.",
         "url":"https://torrentio.strem.fun/videos/failure_unexpected_v2.mp4"}
    ]}"""

    @Test
    fun `une liste vide ou le seul carton d'erreur ne compte pas comme une reponse`() {
        assertFalse(StremioProvider.reponseUtile("""{"streams":[]}"""))
        assertFalse(StremioProvider.reponseUtile(torBoxEnPanne))
        assertFalse(StremioProvider.reponseUtile("<html>502</html>"))
        assertTrue(StremioProvider.reponseUtile(realDebrid))
    }

    @Test
    fun `le debrid se lit dans l'URL de resolution`() {
        assertEquals(DebridService.TORBOX, StremioProvider.debridDe("https://torrentio.strem.fun/resolve/torbox/K/h/null/0/x.mkv"))
        assertEquals(DebridService.REALDEBRID, StremioProvider.debridDe("https://torrentio.strem.fun/resolve/realdebrid/K/h/null/0/x.mkv"))
        assertNull(StremioProvider.debridDe("https://exemple.org/x.mkv"))
    }

    @Test
    fun `le meme fichier chez les deux debrids se suit, le prefere d'abord`() {
        val cible = StremioProvider.CIBLE_EPISODE
        val liens = provider.fusion(listOf(provider.classer(realDebrid, cible, "ja"), provider.classer(torBox, cible, "ja")))
        assertEquals(4, liens.size, "aucun dédoublonnage entre services")
        assertTrue(liens[0].url.contains("/realdebrid/") && liens[0].url.contains("/aaaa/"))
        assertTrue(liens[1].url.contains("/torbox/") && liens[1].url.contains("/aaaa/"), "le secours juste derrière")
        assertTrue(liens.last().deferred, "ce qui est à récupérer reste en dernier")
        assertTrue(liens[0].variant!!.endsWith("Real-Debrid"))
        assertTrue(liens[1].variant!!.endsWith("TorBox"))
    }

    @Test
    fun `l'ordre de priorite s'inverse avec le reglage`() {
        val cible = StremioProvider.CIBLE_EPISODE
        val liens = provider.fusion(listOf(provider.classer(torBox, cible, "ja"), provider.classer(realDebrid, cible, "ja")))
        assertTrue(liens[0].url.contains("/torbox/"))
        assertTrue(liens[1].url.contains("/realdebrid/"))
    }

    @Test
    fun `un seul debrid ne touche pas aux variantes`() {
        val cible = StremioProvider.CIBLE_EPISODE
        val seul = provider.fusion(listOf(provider.classer(realDebrid, cible, "ja")))
        assertEquals(provider.parse(realDebrid, cible, "ja"), seul)
    }
}
