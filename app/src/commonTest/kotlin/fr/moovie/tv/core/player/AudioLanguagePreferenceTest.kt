package fr.moovie.tv.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioLanguagePreferenceTest {

    @Test
    fun `VF prefere le francais quelle que soit la langue originale`() {
        assertEquals(listOf("fr"), preferredAudioLanguages("VF", "ja"))
    }

    @Test
    fun `VO d un anime prefere le japonais au doublage anglais`() {
        assertEquals(listOf("ja"), preferredAudioLanguages("VO", "ja"))
        assertEquals(listOf("ja"), preferredAudioLanguages("VOSTFR", "JA"))
    }

    @Test
    fun `VO sans langue originale connue ne force rien`() {
        assertEquals(emptyList(), preferredAudioLanguages("VO", ""))
        assertEquals(emptyList(), preferredAudioLanguages("VO", "xx"))
        assertEquals(emptyList(), preferredAudioLanguages("", "ja"))
    }

    @Test
    fun `la ligne de provenance distingue le debrid du streaming`() {
        assertEquals(
            "Stremio (debrid) · VO · audio : Japonais",
            playerSourceLine("stremio", "VO", "Japonais"),
        )
        assertEquals("Streaming · voe · VF", playerSourceLine("voe", "VF", ""))
        assertEquals("Fichier téléchargé", playerSourceLine("", "", ""))
    }
}
