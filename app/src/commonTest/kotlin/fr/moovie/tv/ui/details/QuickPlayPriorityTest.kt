package fr.moovie.tv.ui.details

import fr.moovie.tv.core.sources.model.EmbedLink
import kotlin.test.Test
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

    @Test
    fun `le souvenir ne compte que s'il vient de la tete`() {
        assertTrue(souvenirPrioritaire(lien("stremio"), catalogueDeTete = "stremio"))
        assertFalse(souvenirPrioritaire(lien("vidapi"), catalogueDeTete = "stremio"))
        assertTrue(souvenirPrioritaire(lien("vidapi"), catalogueDeTete = null), "tête inconnue : on fait confiance")
    }
}
