package fr.moovie.tv.data.sources

import fr.moovie.tv.core.sources.model.EmbedLink
import fr.moovie.tv.core.sources.port.HttpGateway
import fr.moovie.tv.core.sources.port.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L'aiguillage de Torrentio, rejoué sans réseau : `302` vers le fichier, vers
 * un carton « en cours », vers un carton d'échec, ou `404`.
 */
class StremioExtractorTest {

    private val lien = EmbedLink(
        url = "https://torrentio.strem.fun/resolve/realdebrid/CLE/aed1ca732ef90f09bdaa88520534ad6f6ff7d61d/null/11/S01E01.mkv",
        hoster = StremioExtractor.HOSTER,
        language = "VO",
        variant = "1080p · 1,5 Go",
    )

    private val fichier = "https://sgp1-4.download.real-debrid.com/d/ABC/S01E01.mkv"

    private fun redirige(vararg cibles: String): HttpGateway {
        var appel = 0
        return HttpGateway { request ->
            assertFalse(request.followRedirects, "l'aiguillage se lit sans suivre la redirection")
            val cible = cibles[minOf(appel, cibles.lastIndex)]
            appel++
            HttpResponse(status = 302, url = request.url, headers = mapOf("Location" to cible))
        }
    }

    @Test
    fun `un fichier deja chez le debrid rend son adresse finale`() {
        val flux = runBlocking { StremioExtractor(redirige(fichier)).extract(lien) }
        assertEquals(fichier, flux?.url)
        assertEquals("VO", flux?.language)
    }

    @Test
    fun `le carton en cours fait attendre, puis le fichier arrive`() {
        val passerelle = redirige(
            "https://torrentio.strem.fun/videos/downloading_v3.mp4",
            "https://torrentio.strem.fun/videos/downloading_v3.mp4",
            fichier,
        )
        val flux = runBlocking { StremioExtractor(passerelle, intervalleMs = 1).extract(lien) }
        assertEquals(fichier, flux?.url)
    }

    @Test
    fun `un carton d'echec est un lien mort, pas une video a jouer`() {
        listOf("download_failed_v3.mp4", "failed_opening_v3.mp4", "limits_exceeded_v2.mp4").forEach { carton ->
            val flux = runBlocking {
                StremioExtractor(redirige("https://torrentio.strem.fun/videos/$carton")).extract(lien)
            }
            assertNull(flux, carton)
        }
    }

    @Test
    fun `un 404 de l'addon est un lien mort`() {
        val passerelle = HttpGateway { request -> HttpResponse(status = 404, url = request.url) }
        assertNull(runBlocking { StremioExtractor(passerelle).extract(lien) })
    }

    @Test
    fun `sans reponse de l'addon, le lien part tel quel et le lecteur suivra`() {
        val flux = runBlocking { StremioExtractor(HttpGateway { null }).extract(lien) }
        assertEquals(lien.url, flux?.url)
    }

    @Test
    fun `les cartons se reconnaissent a leur forme, pas a une liste`() {
        assertTrue(StremioExtractor.isStaticVideo("https://torrentio.strem.fun/videos/failed_rar_v3.mp4"))
        assertTrue(StremioExtractor.isStaticVideo("https://torrentio.strem.fun/videos/downloading_v4.mp4"))
        assertFalse(StremioExtractor.isStaticVideo("https://sgp1.download.real-debrid.com/d/X/Film.2021.mp4"))
    }
}
