package fr.moovie.tv.ui.details

import fr.moovie.tv.core.sources.model.EmbedLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickPlayPriorityTest {

    private fun lien(provider: String) = EmbedLink(
        url = "https://$provider.example/ep1",
        hoster = provider,
        language = "VO",
        provider = provider,
    )

    private fun etat(vararg progres: Pair<String, ProviderStatus>) =
        progres.map { (nom, statut) -> ProviderProgress(nom, statut) }

    @Test
    fun `un lien secondaire attend tant que la tete cherche`() {
        val providers = etat("stremio" to ProviderStatus.LOADING, "vidapi" to ProviderStatus.DONE)
        assertTrue(catalogueDeTeteAttendu(providers, lien("vidapi"), toursAttendus = 0))
    }

    @Test
    fun `un lien de la tete part tout de suite`() {
        val providers = etat("stremio" to ProviderStatus.LOADING, "vidapi" to ProviderStatus.DONE)
        assertFalse(catalogueDeTeteAttendu(providers, lien("stremio"), toursAttendus = 0))
    }

    @Test
    fun `la tete qui a repondu, meme vide ou en echec, ne retient plus rien`() {
        listOf(ProviderStatus.DONE, ProviderStatus.EMPTY, ProviderStatus.FAILED).forEach { statut ->
            val providers = etat("stremio" to statut, "vidapi" to ProviderStatus.DONE)
            assertFalse(catalogueDeTeteAttendu(providers, lien("vidapi"), toursAttendus = 0), statut.name)
        }
    }

    @Test
    fun `la patience a une fin`() {
        val providers = etat("stremio" to ProviderStatus.LOADING, "vidapi" to ProviderStatus.DONE)
        assertTrue(catalogueDeTeteAttendu(providers, lien("vidapi"), toursAttendus = PATIENCE_TOURS - 1))
        assertFalse(catalogueDeTeteAttendu(providers, lien("vidapi"), toursAttendus = PATIENCE_TOURS))
    }

    @Test
    fun `sans liste de catalogues, rien n'attend`() {
        assertFalse(catalogueDeTeteAttendu(emptyList(), lien("vidapi"), toursAttendus = 0))
    }

    private val cache = lien("stremio").copy(url = "https://t/resolve/realdebrid/K/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/null/0/a.mkv")
    private val differe = lien("stremio").copy(
        url = "https://t/resolve/realdebrid/K/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/null/0/b.mkv",
        deferred = true,
    )
    private val repondus = etat("stremio" to ProviderStatus.DONE, "vidapi" to ProviderStatus.DONE)
    private val vidapiSeul = etat("stremio" to ProviderStatus.LOADING, "vidapi" to ProviderStatus.DONE)

    private fun suivant(
        links: List<EmbedLink>,
        sansVo: Set<String> = emptySet(),
        providers: List<ProviderProgress> = repondus,
        differeDejaEssaye: Boolean = false,
        lang: String = "VO",
    ) = lienSuivant(links, lang, emptySet(), emptyMap(), emptyMap(), sansVo, providers, differeDejaEssaye)?.url

    @Test
    fun `un lien immediat de la tete passe devant son lien differe`() {
        assertEquals(cache.url, suivant(listOf(differe, cache)))
    }

    @Test
    fun `le differe de la tete passe devant l'immediat d'un autre catalogue`() {
        assertEquals(differe.url, suivant(listOf(lien("vidapi"), differe)))
    }

    @Test
    fun `le differe de la tete attend que la tete ait repondu, puis un seul essai`() {
        val vidapi = lien("vidapi")
        assertEquals(vidapi.url, suivant(listOf(vidapi, differe), providers = vidapiSeul))
        assertEquals(vidapi.url, suivant(listOf(vidapi, differe), differeDejaEssaye = true))
    }

    @Test
    fun `un differe d'un autre catalogue n'est pris qu'une fois tous repondus`() {
        val autre = differe.copy(provider = "vidapi", url = "https://v/x.mp4")
        assertEquals(null, suivant(listOf(autre), providers = vidapiSeul))
        assertEquals(autre.url, suivant(listOf(autre)))
    }

    @Test
    fun `une source connue sans VO est ecartee pour la VO, pas pour la VF`() {
        val sansVo = setOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertEquals(differe.url, suivant(listOf(cache, differe), sansVo))
        val vf = cache.copy(language = "VF")
        assertEquals(vf.url, suivant(listOf(vf), sansVo, lang = "VF"))
    }

    @Test
    fun `le souvenir ne compte que s'il vient de la tete`() {
        assertTrue(souvenirPrioritaire(lien("stremio"), catalogueDeTete = "stremio"))
        assertFalse(souvenirPrioritaire(lien("vidapi"), catalogueDeTete = "stremio"))
        assertTrue(souvenirPrioritaire(lien("vidapi"), catalogueDeTete = null), "tête inconnue : on fait confiance")
    }
}
