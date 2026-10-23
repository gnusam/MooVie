package fr.moovie.tv.data.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals

/** Décodage Windows-1252 des sous-titres de l'addon. */
class StremioSubtitleCatalogTest {

    /** « cœur » : le œ est l'octet 0x9C, juste après la 27e position de l'ancienne table. */
    @Test
    fun cp1252DecodesCoeur() {
        val octets = byteArrayOf(0x63, 0x9C.toByte(), 0x75, 0x72)
        assertEquals("cœur", StremioSubtitleCatalog.cp1252(octets))
    }

    /** Chaque octet de 0x80 à 0x9F se décode sans lever, et selon la table Windows-1252. */
    @Test
    fun cp1252DecodesEveryByteOfTheRange() {
        val attendu = mapOf(
            0x80 to '€', 0x82 to '‚', 0x83 to 'ƒ', 0x84 to '„', 0x85 to '…', 0x86 to '†',
            0x87 to '‡', 0x88 to 'ˆ', 0x89 to '‰', 0x8A to 'Š', 0x8B to '‹', 0x8C to 'Œ',
            0x8E to 'Ž', 0x91 to '‘', 0x92 to '’', 0x93 to '“', 0x94 to '”', 0x95 to '•',
            0x96 to '–', 0x97 to '—', 0x98 to '˜', 0x99 to '™', 0x9A to 'š', 0x9B to '›',
            0x9C to 'œ', 0x9E to 'ž', 0x9F to 'Ÿ',
        )
        for (v in 0x80..0x9F) {
            val sortie = StremioSubtitleCatalog.cp1252(byteArrayOf(v.toByte()))
            // Les cinq positions non définies gardent leur valeur brute.
            assertEquals((attendu[v] ?: v.toChar()).toString(), sortie, "octet 0x${v.toString(16)}")
        }
    }

    /** « Café œ » en cp1252 est invalide en UTF-8 : enUtf8 retombe sur cp1252. */
    @Test
    fun invalidUtf8FallsBackToCp1252() {
        val octets = byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte(), 0x20, 0x9C.toByte())
        assertEquals("Café œ", StremioSubtitleCatalog.enUtf8(octets))
    }
}
