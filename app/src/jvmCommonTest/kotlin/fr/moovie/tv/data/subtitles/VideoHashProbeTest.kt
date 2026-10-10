package fr.moovie.tv.data.subtitles

import fr.moovie.tv.core.sources.model.PlayableStream
import fr.moovie.tv.core.sources.model.StreamFormat
import fr.moovie.tv.core.sources.port.HttpGateway
import fr.moovie.tv.core.sources.port.HttpResponse
import fr.moovie.tv.core.subtitles.usecase.HASH_CHUNK
import fr.moovie.tv.core.subtitles.usecase.openSubtitlesHash
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoHashProbeTest {

    private val taille = 200_000L
    private val tete = ByteArray(HASH_CHUNK) { (it % 251).toByte() }
    private val queue = ByteArray(HASH_CHUNK) { ((it * 7) % 253).toByte() }

    private fun serveur(plages: Boolean = true) = HttpGateway { request ->
        val range = request.headers["Range"].orEmpty()
        if (!plages) return@HttpGateway HttpResponse(200, request.url, bytes = ByteArray(1 shl 20))
        when (range) {
            "bytes=0-${HASH_CHUNK - 1}" -> HttpResponse(
                206, request.url,
                headers = mapOf("Content-Range" to "bytes 0-${HASH_CHUNK - 1}/$taille"),
                bytes = tete,
            )
            "bytes=-$HASH_CHUNK" -> HttpResponse(206, request.url, bytes = queue)
            else -> null
        }
    }

    private val flux = PlayableStream("https://cdn.example/d/X/Film.mkv", StreamFormat.UNKNOWN)

    @Test
    fun `deux plages suffisent a l'empreinte`() {
        val identite = runBlocking { videoIdentity(serveur(), flux) }
        assertEquals(openSubtitlesHash(taille, tete, queue), identite?.hash)
        assertEquals(taille, identite?.sizeBytes)
    }

    @Test
    fun `un serveur qui ignore les plages ne donne pas d'empreinte`() {
        assertNull(runBlocking { videoIdentity(serveur(plages = false), flux) })
    }

    @Test
    fun `un flux segmente n'a pas de fichier`() {
        val hls = PlayableStream("https://cdn.example/x/index.m3u8", StreamFormat.HLS)
        assertNull(runBlocking { videoIdentity(serveur(), hls) })
    }
}
