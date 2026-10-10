package fr.moovie.tv.core.subtitles.usecase

import fr.moovie.tv.core.subtitles.model.SubtitleCandidate
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleMenuOrderTest {

    private fun c(id: String) = SubtitleCandidate(fileId = id, language = "en", release = "rel $id")

    @Test
    fun `l'actif d'abord, puis les pas encore essayes, puis les essayes`() {
        val liste = listOf(c("a"), c("b"), c("c"), c("d"))
        val ordre = orderForMenu(liste, activeId = "c", tried = setOf("a", "c")).map { it.fileId }
        assertEquals(listOf("c", "b", "d", "a"), ordre)
    }

    @Test
    fun `sans actif ni essai, l'ordre du classement est conserve`() {
        val liste = listOf(c("a"), c("b"), c("c"))
        assertEquals(listOf("a", "b", "c"), orderForMenu(liste, null, emptySet()).map { it.fileId })
    }
}
