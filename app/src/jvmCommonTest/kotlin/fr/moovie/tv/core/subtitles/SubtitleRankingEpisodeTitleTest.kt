package fr.moovie.tv.core.subtitles

import fr.moovie.tv.core.subtitles.model.SubtitleCandidate
import fr.moovie.tv.core.subtitles.usecase.rankSubtitles
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleRankingEpisodeTitleTest {

    private val brotherhood = SubtitleCandidate(fileId = "2574458", language = "en", release = "Fullmetal.Alchemist.Brotherhood.E02.720p.BDRip", downloads = 22591)
    private val sanctioned = SubtitleCandidate(fileId = "2574729", language = "en", release = "02 - Body of the Sanctioned", downloads = 12221)
    private val shinsen = SubtitleCandidate(fileId = "2585267", language = "en", release = "[Shinsen-Subs]_Fullmetal_Alchemist_2_-_Brothers", downloads = 1910)

    @Test
    fun `le titre de l'episode dans la release passe devant la popularite`() {
        val classes = rankSubtitles(listOf(brotherhood, shinsen, sanctioned), listOf("fr", "en"), episodeTitle = "Body of the Sanctioned")
        assertEquals(listOf("2574729", "2574458", "2585267"), classes.map { it.fileId })
    }

    @Test
    fun `sans titre d'episode, la popularite decide comme avant`() {
        val classes = rankSubtitles(listOf(sanctioned, brotherhood, shinsen), listOf("fr", "en"))
        assertEquals("2574458", classes.first().fileId)
    }

    @Test
    fun `la ponctuation de la release ne gene pas la correspondance`() {
        val points = sanctioned.copy(fileId = "x", release = "FMA.02.Body.of.the.Sanctioned.DVDRip", downloads = 1)
        val classes = rankSubtitles(listOf(brotherhood, points), listOf("en"), episodeTitle = "Body of the Sanctioned")
        assertEquals("x", classes.first().fileId)
    }

    @Test
    fun `le titre francais de TMDB ne suffit pas, l'anglais rattrape`() {
        val classes = rankSubtitles(
            listOf(brotherhood, sanctioned),
            listOf("en"),
            episodeTitle = "Le Corps des sacrifiés",
            episodeTitles = listOf("Body of the Sanctioned"),
        )
        assertEquals("2574729", classes.first().fileId)
    }
}
