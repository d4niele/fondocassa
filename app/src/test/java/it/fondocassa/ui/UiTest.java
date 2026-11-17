package it.fondocassa.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import it.fondocassa.R;
import it.fondocassa.TestApp;
import it.fondocassa.data.Alunno;
import it.fondocassa.data.Entrata;
import it.fondocassa.data.Quota;
import it.fondocassa.security.PinHasher;

@RunWith(AndroidJUnit4.class)
public class UiTest {
    private TestApp app;

    @Before
    public void prepara() {
        app = ApplicationProvider.getApplicationContext();
    }

    /** Attende le operazioni in background e i relativi aggiornamenti dell'interfaccia. */
    private void attendi() throws Exception {
        for (int i = 0; i < 3; i++) {
            app.io().submit(() -> { }).get();
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private void scrivi(android.app.Activity activity, String testo) {
        ((EditText) activity.findViewById(R.id.campo_pin)).setText(testo);
        activity.findViewById(R.id.conferma).performClick();
    }

    @Test
    public void alPrimoAvvioVieneChiestoDiCreareIlPin() {
        assertTrue(app.bloccata());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Intent avviata = shadowOf(activity).getNextStartedActivity();
                assertNotNull("l'app bloccata deve mostrare la schermata del PIN", avviata);
                assertEquals(LockActivity.class.getName(), avviata.getComponent().getClassName());
            });
        }
    }

    @Test
    public void creazioneDelPinESbloccoSuccessivo() throws Exception {
        try (ActivityScenario<LockActivity> scenario = ActivityScenario.launch(LockActivity.class)) {
            scenario.onActivity(a -> {
                assertEquals(a.getString(R.string.pin_crea), ((TextView) a.findViewById(R.id.messaggio)).getText());
                scrivi(a, "12"); // troppo corto: resta sulla stessa fase
                assertEquals(a.getString(R.string.pin_crea), ((TextView) a.findViewById(R.id.messaggio)).getText());
                scrivi(a, "1234");
                assertEquals(a.getString(R.string.pin_conferma), ((TextView) a.findViewById(R.id.messaggio)).getText());
                scrivi(a, "1234");
            });
            attendi();
        }
        assertTrue(PinHasher.verifica("1234", app.settings().pinHash()));
        assertFalse(app.bloccata());
    }

    @Test
    public void pinErratoNonSblocca() throws Exception {
        app.settings().setPinHash(PinHasher.hash("1234"));
        try (ActivityScenario<LockActivity> scenario = ActivityScenario.launch(LockActivity.class)) {
            scenario.onActivity(a -> scrivi(a, "9999"));
            attendi();
            assertTrue(app.bloccata());
            scenario.onActivity(a -> scrivi(a, "1234"));
            attendi();
            assertFalse(app.bloccata());
        }
    }

    @Test
    public void ilRiepilogoMostraCassaRealeVirtualeEQuoteDaVersare() throws Exception {
        app.sblocca();
        Quota quota = new Quota();
        quota.descrizione = "Quota di avvio";
        quota.importo = 500;
        app.repository().salvaQuota(quota);
        String anna = nuovoAlunno("Anna Rossi");
        nuovoAlunno("Marco Verdi");
        Entrata e = new Entrata();
        e.alunnoId = anna;
        e.importo = 500;
        e.data = "2026-11-16";
        app.repository().salvaEntrata(e);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            attendi();
            scenario.onActivity(a -> {
                assertEquals("€ 5,00", testo(a, R.id.testo_cassa_reale));
                assertEquals("€ 10,00", testo(a, R.id.testo_cassa_virtuale));
                assertEquals("€ 10,00", testo(a, R.id.testo_previsto));
                assertEquals("€ 5,00", testo(a, R.id.testo_versato));
                assertEquals("€ 5,00", testo(a, R.id.testo_da_versare));
                assertEquals(a.getString(R.string.alunni_in_regola_valore, 1, 2), testo(a, R.id.testo_in_regola));
                assertEquals("€ 0,00", testo(a, R.id.testo_fondo_iniziale));
                assertEquals(a.getString(R.string.fondo_iniziale_vuoto), testo(a, R.id.testo_fondo_dettaglio));
            });

            app.repository().salvaFondoIniziale(3750, "Avanzo a.s. 2025/26", "2026-09-01");
            attendi();
            scenario.onActivity(a -> {
                assertEquals("€ 37,50", testo(a, R.id.testo_fondo_iniziale));
                assertEquals("Avanzo a.s. 2025/26 · 01/09/2026", testo(a, R.id.testo_fondo_dettaglio));
                assertEquals("€ 42,50", testo(a, R.id.testo_cassa_reale));
                assertEquals("€ 47,50", testo(a, R.id.testo_cassa_virtuale));
                assertEquals("€ 5,00", testo(a, R.id.testo_versato));
            });
        }
    }

    @Test
    public void incassoRapidoDallaSchedaAlunni() throws Exception {
        app.sblocca();
        Quota quota = new Quota();
        quota.descrizione = "Quota di avvio";
        quota.importo = 500;
        app.repository().salvaQuota(quota);
        String anna = nuovoAlunno("Anna Rossi");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a ->
                    ((BottomNavigationView) a.findViewById(R.id.navigazione)).setSelectedItemId(R.id.tab_alunni));
            attendi();
            scenario.onActivity(a -> {
                RecyclerView lista = a.findViewById(R.id.lista);
                lista.measure(0, 0);
                lista.layout(0, 0, 1000, 2000);
                assertEquals(1, lista.getAdapter().getItemCount());
                lista.getChildAt(0).performClick();
            });
            attendi();
            scenario.onActivity(a -> {
                Fragment alunni = a.getSupportFragmentManager().findFragmentById(R.id.contenuto);
                IncassoSheet sheet = (IncassoSheet) alunni.getChildFragmentManager().findFragmentByTag("incasso");
                assertNotNull(sheet);
                // L'importo è già compilato con il residuo: basta confermare.
                assertEquals("5,00",
                        ((EditText) sheet.requireView().findViewById(R.id.campo_importo)).getText().toString());
                sheet.requireView().findViewById(R.id.registra).performClick();
            });
            attendi();
        }

        List<Entrata> registrate = app.db().entrate().pendenti();
        assertEquals(1, registrate.size());
        assertEquals(anna, registrate.get(0).alunnoId);
        assertEquals(500, registrate.get(0).importo);
        assertEquals("Contante", registrate.get(0).metodo);
        assertEquals(500, app.db().alunni().listConSaldo().get(0).totaleVersato);
    }

    @Test
    public void conLaListaVuotaSiPuoImportareLElenco() throws Exception {
        app.sblocca();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a ->
                    ((BottomNavigationView) a.findViewById(R.id.navigazione)).setSelectedItemId(R.id.tab_alunni));
            attendi();
            scenario.onActivity(a -> {
                assertEquals(android.view.View.VISIBLE, a.findViewById(R.id.vuoto_box).getVisibility());
                assertEquals(a.getString(R.string.importa_alunni), testo(a, R.id.azione_vuoto));
                AlunniFragment alunni =
                        (AlunniFragment) a.getSupportFragmentManager().findFragmentById(R.id.contenuto);
                assertNotNull("un elenco non valido viene segnalato", alunni.importa("{\"classe\":1}"));
                assertEquals(null, alunni.importa(
                        "nome;cognome;note\nMarco;Verdi;\nAnna;Rossi;Genitori: Paola e Luca\n"));
            });
            attendi();
            scenario.onActivity(a ->
                    assertEquals(android.view.View.GONE, a.findViewById(R.id.vuoto_box).getVisibility()));
        }

        List<it.fondocassa.data.AlunnoConSaldo> classe = app.db().alunni().listConSaldo();
        assertEquals(2, classe.size());
        assertEquals("Rossi Anna", classe.get(0).alunno.nome);
        assertEquals("Genitori: Paola e Luca", classe.get(0).alunno.note);
        assertEquals("Verdi Marco", classe.get(1).alunno.nome);
        assertFalse(classe.get(0).alunno.synced);
    }

    private String nuovoAlunno(String nome) {
        Alunno a = new Alunno();
        a.nome = nome;
        app.repository().salvaAlunno(a);
        return a.id;
    }

    private static String testo(android.app.Activity a, int id) {
        return ((TextView) a.findViewById(id)).getText().toString();
    }
}
