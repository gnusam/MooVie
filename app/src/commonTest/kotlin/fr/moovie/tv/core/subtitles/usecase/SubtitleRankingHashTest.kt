package fr.moovie.tv.core.subtitles.usecase

import fr.moovie.tv.core.subtitles.model.SubtitleCandidate
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleRankingHashTest {

    private fun c(id: String, release: String, downloads: Int = 0, hashMatch: Boolean = false, lang: String = "en") =
        SubtitleCandidate(fileId = id, language = lang, release = release, downloads = downloads, hashMatch = hashMatch)

    @Test
    fun `l'empreinte exacte passe devant la popularite et le titre d'episode`() {
        val liste = listOf(
            c("pop", "I'm Luffy! The Man Who Will Become the Pirate King", downloads = 228_164),
            c("exact", "One.Piece.S01E01.WEB-FORSEE", downloads = 3, hashMatch = true),
        )
        val ordre = rankSubtitles(liste, listOf("en"), episodeTitle = "I'm Luffy!").map { it.fileId }
        assertEquals(listOf("exact", "pop"), ordre)
    }

    @Test
    fun `le meme groupe de release passe devant les autres, apres l'empreinte`() {
        val liste = listOf(
            c("autre", "Castamar.S01E01.1080p.WEB.DDP5.1-WildBrian", downloads = 900),
            c("groupe", "The.Cook.of.Castamar.S01E01.720p.WEB.H264-FORSEE", downloads = 10),
        )
        val ordre = rankSubtitles(
            liste,
            listOf("en"),
            sourceRelease = "The.Cook.of.Castamar.S01E01.720p.WEB.H264-FORSEE.mkv",
        ).map { it.fileId }
        assertEquals(listOf("groupe", "autre"), ordre)
    }

    @Test
    fun `la langue voulue reste premiere, empreinte ou pas`() {
        val liste = listOf(
            c("en-exact", "x", hashMatch = true, lang = "en"),
            c("fr", "y", lang = "fr"),
        )
        assertEquals(listOf("fr", "en-exact"), rankSubtitles(liste, listOf("fr", "en")).map { it.fileId })
    }
}
