package it.fondocassa.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.AppDatabase;
import it.fondocassa.data.Fondo;
import it.fondocassa.data.Settings;
import it.fondocassa.sync.ApiClient;
import it.fondocassa.sync.ApiModels.BackupResponse;
import it.fondocassa.sync.SyncManager;
import it.fondocassa.sync.SyncWorker;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;

/** Collegamento allo script, fondo iniziale e nuovo anno, ripristino da Google Sheets e sicurezza. */
public class SettingsActivity extends BaseActivity {
    private EditText campoEndpoint;
    private EditText campoToken;
    private View attesa;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applicaMargini(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.azione_impostazioni);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        Settings settings = app().settings();
        campoEndpoint = findViewById(R.id.campo_endpoint);
        campoToken = findViewById(R.id.campo_token);
        attesa = findViewById(R.id.attesa);
        if (savedInstanceState == null) {
            campoEndpoint.setText(settings.endpoint());
            campoToken.setText(settings.token());
        }

        findViewById(R.id.salva).setOnClickListener(v -> {
            if (salva()) {
                avviso(getString(R.string.impostazioni_salvate));
            }
        });
        findViewById(R.id.prova).setOnClickListener(v -> provaConnessione());
        findViewById(R.id.ripristina).setOnClickListener(v -> chiediRipristino());
        findViewById(R.id.reinvia).setOnClickListener(v -> chiediReinvio());
        findViewById(R.id.esporta_backup).setOnClickListener(v -> esportaBackup());
        findViewById(R.id.azzera).setOnClickListener(v -> chiediAzzeramento());
        Button bottoneFondo = findViewById(R.id.fondo_iniziale);
        app().db().fondi().observe(Fondo.ID_INIZIALE).observe(this, fondo -> {
            bottoneFondo.setText(getString(R.string.fondo_iniziale_valore,
                    Money.format(fondo == null ? 0 : fondo.importo)));
            bottoneFondo.setOnClickListener(v -> FondoDialog.mostra(this, fondo));
        });
        findViewById(R.id.cambia_pin).setOnClickListener(v ->
                startActivity(new Intent(this, LockActivity.class).putExtra(LockActivity.EXTRA_CAMBIO, true)));

        MaterialSwitch biometria = findViewById(R.id.biometria);
        biometria.setChecked(settings.biometria());
        biometria.setOnCheckedChangeListener((bottone, attiva) -> {
            if (attiva && !LockActivity.biometriaDisponibile(this)) {
                bottone.setChecked(false);
                avviso(getString(R.string.biometria_non_disponibile));
                return;
            }
            settings.setBiometria(attiva);
        });

        ((TextView) findViewById(R.id.versione)).setText(getString(R.string.versione, versione()));
    }

    private String versione() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Valida e salva i campi. @return false se c'è un errore di validazione */
    private boolean salva() {
        TextInputLayout layoutEndpoint = findViewById(R.id.layout_endpoint);
        layoutEndpoint.setError(null);
        String endpoint = testo(campoEndpoint);
        if (!endpoint.isEmpty() && !endpoint.startsWith("https://")) {
            layoutEndpoint.setError(getString(R.string.errore_https));
            return false;
        }
        Settings settings = app().settings();
        settings.setConnessione(endpoint, testo(campoToken));
        if (settings.connessioneConfigurata()) {
            SyncWorker.accoda(this);
        }
        return true;
    }

    private void provaConnessione() {
        if (!salva()) {
            return;
        }
        Settings settings = app().settings();
        if (!settings.connessioneConfigurata()) {
            avviso(getString(R.string.sync_non_configurata));
            return;
        }
        attesa.setVisibility(View.VISIBLE);
        app().io().execute(() -> {
            try {
                new ApiClient(settings.endpoint(), settings.token()).ping();
                mostraEsito(getString(R.string.connessione_ok));
            } catch (IOException e) {
                mostraFallimento(R.string.connessione_errore_titolo, e);
            }
        });
    }

    private void chiediRipristino() {
        if (!salva()) {
            return;
        }
        FondocassaApp app = app();
        if (!app.settings().connessioneConfigurata()) {
            avviso(getString(R.string.sync_non_configurata));
            return;
        }
        app.io().execute(() -> {
            int pendenti = app.db().alunni().pendenti().size() + app.db().quote().pendenti().size()
                    + app.db().entrate().pendenti().size()
                    + app.db().spese().pendenti().size();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.ripristina)
                        .setMessage(pendenti > 0
                                ? getString(R.string.ripristina_conferma_pendenti, pendenti)
                                : getString(R.string.ripristina_conferma))
                        .setPositiveButton(R.string.ripristina, (d, w) -> ripristina())
                        .setNegativeButton(R.string.azione_annulla, null)
                        .show();
            });
        });
    }

    private void ripristina() {
        FondocassaApp app = app();
        attesa.setVisibility(View.VISIBLE);
        avviso(getString(R.string.ripristina_in_corso));
        app.io().execute(() -> {
            try {
                int importati = SyncManager.crea(app.db(), app.settings()).ripristina();
                mostraEsito(getString(R.string.ripristina_ok, importati));
            } catch (IOException e) {
                mostraFallimento(R.string.ripristina_errore_titolo, e);
            }
        });
    }

    private void chiediReinvio() {
        if (!salva()) {
            return;
        }
        if (!app().settings().connessioneConfigurata()) {
            avviso(getString(R.string.sync_non_configurata));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reinvia)
                .setMessage(R.string.reinvia_conferma)
                .setPositiveButton(R.string.reinvia_azione, (d, w) -> reinvia())
                .setNegativeButton(R.string.azione_annulla, null)
                .show();
    }

    private void reinvia() {
        FondocassaApp app = app();
        attesa.setVisibility(View.VISIBLE);
        avviso(getString(R.string.reinvia_in_corso));
        app.io().execute(() -> {
            try {
                SyncManager.Esito esito = SyncManager.crea(app.db(), app.settings()).reinviaTutto();
                if (esito.giustificativiFalliti > 0) {
                    runOnUiThread(() -> {
                        attesa.setVisibility(View.GONE);
                        mostraErrore(getString(R.string.sync_giustificativi_titolo),
                                getString(R.string.sync_giustificativi_errore,
                                        esito.giustificativiFalliti, esito.erroreGiustificativi));
                    });
                } else {
                    mostraEsito(getString(R.string.reinvia_ok, esito.inviati));
                }
            } catch (IOException e) {
                mostraFallimento(R.string.reinvia_errore_titolo, e);
            }
        });
    }

    /** Azzeramento di inizio anno: va confermato scrivendo la parola indicata. */
    private void chiediAzzeramento() {
        View vista = getLayoutInflater().inflate(R.layout.dialog_azzera, null);
        MaterialCheckBox mantieniAlunni = vista.findViewById(R.id.mantieni_alunni);
        MaterialCheckBox riportaCassa = vista.findViewById(R.id.riporta_cassa);
        riportaCassa.setText(getString(R.string.azzera_riporta_cassa, "…"));
        FondocassaApp app = app();
        app.io().execute(() -> {
            String cassa = Money.format(app.repository().cassaReale());
            runOnUiThread(() -> riportaCassa.setText(getString(R.string.azzera_riporta_cassa, cassa)));
        });
        EditText conferma = vista.findViewById(R.id.campo_conferma);
        String parola = getString(R.string.azzera_parola);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.azzera)
                .setView(vista)
                .setPositiveButton(R.string.azzera_azione, (d, w) -> azzera(mantieniAlunni.isChecked(), riportaCassa.isChecked()))
                .setNegativeButton(R.string.azione_annulla, null)
                .show();
        Button azione = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        azione.setEnabled(false);
        conferma.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                azione.setEnabled(parola.equalsIgnoreCase(s.toString().trim()));
            }
        });
    }

    private void azzera(boolean mantieniAlunni, boolean riportaCassa) {
        FondocassaApp app = app();
        attesa.setVisibility(View.VISIBLE);
        app.io().execute(() -> {
            app.repository().azzeraTutto(mantieniAlunni,
                    riportaCassa ? getString(R.string.fondo_descrizione_riporto) : null, Date.oggi());
            if (!app.settings().connessioneConfigurata()) {
                mostraEsito(getString(R.string.azzera_ok_locale));
                return;
            }
            try {
                SyncManager.crea(app.db(), app.settings()).sincronizza();
                mostraEsito(getString(R.string.azzera_ok));
            } catch (IOException e) {
                // I dati sul telefono sono già azzerati: il foglio si allinea alla prossima sincronizzazione.
                runOnUiThread(() -> {
                    attesa.setVisibility(View.GONE);
                    mostraErrore(getString(R.string.azzera_foglio_in_attesa_titolo),
                            getString(R.string.azzera_foglio_in_attesa, e.getMessage()));
                });
            }
        });
    }

    /** Crea su Drive lo ZIP con database e giustificativi. */
    private void esportaBackup() {
        if (!salva()) {
            return;
        }
        FondocassaApp app = app();
        if (!app.settings().connessioneConfigurata()) {
            avviso(getString(R.string.sync_non_configurata));
            return;
        }
        attesa.setVisibility(View.VISIBLE);
        avviso(getString(R.string.backup_in_corso));
        app.io().execute(() -> {
            try {
                BackupResponse backup = SyncManager.crea(app.db(), app.settings())
                        .esportaBackup(getDatabasePath(AppDatabase.NOME_FILE));
                runOnUiThread(() -> mostraBackup(backup));
            } catch (IOException e) {
                mostraFallimento(R.string.backup_errore_titolo, e);
            }
        });
    }

    private void mostraBackup(BackupResponse backup) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        attesa.setVisibility(View.GONE);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_ok)
                .setMessage(getString(R.string.backup_dettaglio, backup.nome, backup.giustificativi))
                .setPositiveButton(R.string.giustificativo_apri, (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(backup.url)));
                    } catch (ActivityNotFoundException e) {
                        avviso(getString(R.string.nessuna_app));
                    }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void mostraFallimento(int titolo, IOException errore) {
        runOnUiThread(() -> {
            attesa.setVisibility(View.GONE);
            mostraErrore(getString(titolo), errore.getMessage());
        });
    }

    private void mostraEsito(String esito) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            attesa.setVisibility(View.GONE);
            avviso(esito);
        });
    }

    private void avviso(String testo) {
        Snackbar.make(findViewById(android.R.id.content), testo, Snackbar.LENGTH_LONG).show();
    }
}
