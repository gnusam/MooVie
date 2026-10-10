package fr.moovie.tv.core.sources.usecase

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceIdentityTest {

    @Test
    fun `un lien d'addon s'identifie par son infohash, le meme pour tout le pack`() {
        val e1 = "https://torrentio.strem.fun/resolve/realdebrid/CLE/AED1CA732EF90F09BDAA88520534AD6F6FF7D61D/null/11/S01E01.mkv"
        val e2 = "https://torrentio.strem.fun/resolve/realdebrid/CLE/aed1ca732ef90f09bdaa88520534ad6f6ff7d61d/null/12/S01E02.mkv"
        assertEquals("aed1ca732ef90f09bdaa88520534ad6f6ff7d61d", sourceIdentity(e1))
        assertEquals(sourceIdentity(e1), sourceIdentity(e2))
    }

    @Test
    fun `tout autre lien s'identifie par lui-meme`() {
        val url = "https://frembed.casa/api/public/v1/tv/1396?sa=1&epi=1"
        assertEquals(url, sourceIdentity(url))
    }
}
