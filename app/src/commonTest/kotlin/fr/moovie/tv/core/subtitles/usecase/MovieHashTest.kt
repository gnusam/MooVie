package fr.moovie.tv.core.subtitles.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovieHashTest {

    /** Vecteur synthétique, calculé à part avec l'algorithme de référence. */
    @Test
    fun `l'empreinte suit l'algorithme OpenSubtitles`() {
        val tete = ByteArray(HASH_CHUNK) { (it % 251).toByte() }
        val queue = ByteArray(HASH_CHUNK) { ((it * 7) % 253).toByte() }
        assertEquals("db8b3defab610086", openSubtitlesHash(200_000, tete, queue))
    }

    @Test
    fun `une plage tronquee ne donne aucune empreinte`() {
        assertNull(openSubtitlesHash(200_000, ByteArray(HASH_CHUNK - 1), ByteArray(HASH_CHUNK)))
        assertNull(openSubtitlesHash(1_000, ByteArray(HASH_CHUNK), ByteArray(HASH_CHUNK)))
    }

    @Test
    fun `le groupe de release se lit en queue ou entre crochets`() {
        assertEquals("forsee", releaseGroup("https://cdn/d/X/The.Cook.of.Castamar.S01E01.720p.WEB.H264-FORSEE.mkv"))
        assertEquals("erai-raws", releaseGroup("[Erai-raws] One Piece - 01 [1080p][Multiple Subtitle].mkv"))
        assertEquals("ion10", releaseGroup("The.Cook.of.Castamar.S01E01.WEBRip.x264-ION10.mp4"))
        assertNull(releaseGroup("S01E01_The Essential Ingredient.mkv"))
        assertNull(releaseGroup("video.mp4"))
    }

    @Test
    fun `une release du meme groupe se reconnait`() {
        assertTrue(sameReleaseGroup("Castamar.S01E01.WEB.H264-FORSEE", "forsee"))
        assertFalse(sameReleaseGroup("The.Cook.of.Castamar.S01.SPANISH.WEBRip.x264-ION10", "forsee"))
        assertFalse(sameReleaseGroup("quoi que ce soit", null))
    }
}
