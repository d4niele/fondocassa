package it.fondocassa.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

import it.fondocassa.TestApp;

@RunWith(AndroidJUnit4.class)
public class DatabaseTest {
    private AppDatabase db;
    private Repository repo;
    private int modifiche;
    private long orologio = 1000;

    @Before
    public void apri() {
        db = TestApp.dbInMemoria(ApplicationProvider.getApplicationContext());
        repo = new Repository(db, () -> modifiche++, () -> orologio++);
    }

    @After
    public void chiudi() {
        db.close();
    }

    private Alunno alunno(String nome) {
        Alunno a = new Alunno();
        a.nome = nome;
        repo.salvaAlunno(a);
        return a;
    }

    private Entrata entrata(Alunno a, long importo) {
        Entrata e = new Entrata();
        e.alunnoId = a.id;
        e.importo = importo;
        e.data = "2026-11-16";
        repo.salvaEntrata(e);
        return e;
    }

    private Quota quota(String descrizione, long importo) {
        Quota q = new Quota();
        q.descrizione = descrizione;
        q.importo = importo;
        q.data = "2026-09-15";
        repo.salvaQuota(q);
        return q;
    }

    @Test
    public void ogniSalvataggioNasceNonSincronizzatoENotificaLaModifica() {
        Alunno a = alunno("Anna Rossi");
        Entrata e = entrata(a, 500);

        assertFalse(a.id.isEmpty());
        assertFalse(db.alunni().get(a.id).synced);
        assertFalse(db.entrate().get(e.id).synced);
        assertEquals(1, db.alunni().pendenti().size());
        assertEquals(1, db.entrate().pendenti().size());
        assertEquals(2, modifiche);
    }

    @Test
    public void totaleVersatoPerAlunnoInOrdineAlfabetico() {
        Alunno marco = alunno("marco Verdi");
        Alunno anna = alunno("Anna Rossi");
        entrata(anna, 300);
        entrata(anna, 200);
        entrata(marco, 100);

        List<AlunnoConSaldo> classe = db.alunni().listConSaldo();

        assertEquals("Anna Rossi", classe.get(0).alunno.nome);
        assertEquals(500, classe.get(0).totaleVersato);
        assertEquals("marco Verdi", classe.get(1).alunno.nome);
        assertEquals(100, classe.get(1).totaleVersato);
        assertEquals(600, db.entrate().totale());
    }

    @Test
    public void ilDovutoPerAlunnoELaSommaDelleQuote() {
        quota("Quota di avvio", 500);
        Quota gita = quota("Gita", 1000);
        assertEquals(1500, db.quote().totale());

        repo.eliminaQuota(gita.id);
        assertEquals(500, db.quote().totale());
    }

    @Test
    public void ancheUnRecordSenzaConfermaDiSyncVieneMarcatoENonCancellato() {
        // Senza conferma non si sa se la riga è già sul foglio (invio in corso, risposta persa):
        // la cancellazione deve comunque arrivare allo script.
        Alunno a = alunno("Anna");
        Entrata e = entrata(a, 500);
        int modifichePrima = modifiche;

        repo.eliminaEntrata(e.id);

        Entrata salvata = db.entrate().get(e.id);
        assertTrue(salvata.eliminato);
        assertFalse(salvata.synced);
        assertEquals(1, db.entrate().pendenti().size());
        assertEquals(modifichePrima + 1, modifiche);
        assertEquals(0, db.entrate().totale());
    }

    @Test
    public void unRecordGiaSulFoglioVieneSoloMarcatoPerPropagareLaCancellazione() {
        Alunno a = alunno("Anna");
        Entrata e = entrata(a, 500);
        db.entrate().confermaSync(e.id, db.entrate().get(e.id).aggiornato, "2026-11-16T10:00:00Z");
        assertTrue(db.entrate().get(e.id).synced);

        repo.eliminaEntrata(e.id);

        Entrata salvata = db.entrate().get(e.id);
        assertTrue(salvata.eliminato);
        assertFalse(salvata.synced);
        assertEquals(0, db.entrate().totale());
        assertEquals(0, db.alunni().listConSaldo().get(0).totaleVersato);

        // Dopo la conferma dello script il record sparisce davvero.
        db.entrate().confermaSync(e.id, salvata.aggiornato, "2026-11-16T11:00:00Z");
        db.entrate().rimuoviEliminatiSincronizzati();
        assertNull(db.entrate().get(e.id));
    }

    @Test
    public void laConfermaNonCopreUnaModificaFattaDuranteLInvio() {
        Alunno a = alunno("Anna");
        Entrata e = entrata(a, 500);
        long versioneInviata = db.entrate().get(e.id).aggiornato;

        e.importo = 700; // modifica arrivata mentre la sync era in corso
        repo.salvaEntrata(e);
        db.entrate().confermaSync(e.id, versioneInviata, "2026-11-16T10:00:00Z");

        assertFalse(db.entrate().get(e.id).synced);
    }

    @Test
    public void unAlunnoConQuoteVersateNonPuoEssereEliminato() {
        Alunno a = alunno("Anna");
        Entrata e = entrata(a, 500);

        assertFalse(repo.eliminaAlunno(a.id));
        assertEquals(1, db.alunni().list().size());

        repo.eliminaEntrata(e.id);
        assertTrue(repo.eliminaAlunno(a.id));
        assertEquals(0, db.alunni().list().size());
    }

    @Test
    public void eliminareUnaSpesaCancellaAncheIlFileAllegato() throws Exception {
        File allegato = File.createTempFile("scontrino", ".jpg");
        Spesa s = new Spesa();
        s.descrizione = "Gessi";
        s.importo = 350;
        s.data = "2026-10-01";
        s.giustificativoLocale = allegato.getAbsolutePath();
        repo.salvaSpesa(s);
        assertTrue(db.spese().get(s.id).uploadInAttesa());
        assertEquals(350, db.spese().totale());

        repo.eliminaSpesa(s.id);

        assertFalse(allegato.exists());
        Spesa salvata = db.spese().get(s.id);
        assertTrue(salvata.eliminato);
        assertNull(salvata.giustificativoLocale);
        assertEquals(0, db.spese().totale());
    }

    @Test
    public void azzerareEliminaTuttoTranneGliAlunniSeRichiesto() throws Exception {
        Alunno a = alunno("Anna");
        entrata(a, 500);
        quota("Quota di avvio", 500);
        File allegato = File.createTempFile("scontrino", ".jpg");
        Spesa s = new Spesa();
        s.descrizione = "Gessi";
        s.importo = 350;
        s.giustificativoLocale = allegato.getAbsolutePath();
        repo.salvaSpesa(s);
        db.alunni().confermaSync(a.id, db.alunni().get(a.id).aggiornato, "2026-11-16T10:00:00Z");
        int modifichePrima = modifiche;

        repo.azzeraTutto(true);

        assertEquals(0, db.entrate().totale());
        assertEquals(0, db.spese().totale());
        assertEquals(0, db.quote().totale());
        assertFalse(allegato.exists());
        // Le eliminazioni restano in attesa per arrivare al foglio; gli alunni non vengono toccati.
        assertEquals(1, db.entrate().pendenti().size());
        assertEquals(1, db.spese().pendenti().size());
        assertEquals(1, db.quote().pendenti().size());
        assertEquals(1, db.alunni().list().size());
        assertTrue(db.alunni().get(a.id).synced);
        assertEquals(0, db.alunni().listConSaldo().get(0).totaleVersato);
        assertEquals(modifichePrima + 1, modifiche);

        repo.azzeraTutto(false);

        assertEquals(0, db.alunni().list().size());
        assertTrue(db.alunni().get(a.id).eliminato);
        assertFalse(db.alunni().get(a.id).synced);
    }

    @Test
    public void leCategorieUsateVengonoRiproposte() {
        for (String categoria : new String[]{"Gite", "Feste", "Gite", ""}) {
            Spesa s = new Spesa();
            s.descrizione = "x";
            s.categoria = categoria;
            repo.salvaSpesa(s);
        }
        assertEquals(List.of("Feste", "Gite"), db.spese().categorie());
    }
}
