package it.fondocassa.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.sync.SyncManager;

public class MainActivity extends BaseActivity {
    private boolean syncInCorso;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applicaMargini(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.azione_sincronizza) {
                sincronizza();
                return true;
            }
            if (item.getItemId() == R.id.azione_impostazioni) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });

        BottomNavigationView nav = findViewById(R.id.navigazione);
        nav.setOnItemSelectedListener(item -> {
            mostra(item.getItemId());
            return true;
        });
        if (savedInstanceState == null) {
            mostra(R.id.tab_riepilogo);
        }
    }

    private void mostra(int tab) {
        Fragment fragment;
        if (tab == R.id.tab_alunni) {
            fragment = new AlunniFragment();
        } else if (tab == R.id.tab_quote) {
            fragment = new QuoteFragment();
        } else if (tab == R.id.tab_entrate) {
            fragment = new EntrateFragment();
        } else if (tab == R.id.tab_spese) {
            fragment = new SpeseFragment();
        } else {
            fragment = new RiepilogoFragment();
        }
        getSupportFragmentManager().beginTransaction().replace(R.id.contenuto, fragment).commit();
    }

    /** Sincronizzazione manuale (pulsante "Sincronizza"). */
    public void sincronizza() {
        FondocassaApp app = app();
        if (!app.settings().connessioneConfigurata()) {
            avviso(getString(R.string.sync_non_configurata));
            return;
        }
        if (syncInCorso) {
            return;
        }
        syncInCorso = true;
        avviso(getString(R.string.sync_in_corso));
        app.io().execute(() -> {
            String messaggio = null;
            String titoloErrore = null;
            String errore = null;
            try {
                SyncManager.Esito esito = SyncManager.crea(app.db(), app.settings()).sincronizza();
                if (esito.giustificativiFalliti > 0) {
                    titoloErrore = getString(R.string.sync_giustificativi_titolo);
                    errore = getString(R.string.sync_giustificativi_errore,
                            esito.giustificativiFalliti, esito.erroreGiustificativi);
                } else {
                    messaggio = esito.inviati == 0 && esito.giustificativiCaricati == 0
                            ? getString(R.string.sync_nulla)
                            : getString(R.string.sync_ok, esito.inviati);
                }
            } catch (IOException e) {
                titoloErrore = getString(R.string.sync_errore_titolo);
                errore = e.getMessage();
            }
            String finale = messaggio;
            String titoloFinale = titoloErrore;
            String erroreFinale = errore;
            runOnUiThread(() -> {
                syncInCorso = false;
                if (!isFinishing() && !isDestroyed()) {
                    if (titoloFinale != null) {
                        mostraErrore(titoloFinale, erroreFinale);
                    } else {
                        avviso(finale);
                    }
                    // Aggiorna "ultima sincronizzazione" nel riepilogo.
                    Fragment attuale = getSupportFragmentManager().findFragmentById(R.id.contenuto);
                    if (attuale instanceof RiepilogoFragment) {
                        ((RiepilogoFragment) attuale).aggiorna();
                    }
                }
            });
        });
    }

    private void avviso(String testo) {
        Snackbar.make(findViewById(R.id.contenuto), testo, Snackbar.LENGTH_LONG).show();
    }
}
