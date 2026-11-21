package it.fondocassa.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.Repository;
import it.fondocassa.data.Spesa;
import it.fondocassa.util.Date;
import it.fondocassa.util.Giustificativi;
import it.fondocassa.util.Money;

/** Inserimento o modifica di una spesa, con foto dello scontrino o PDF allegato. */
public class SpesaEditActivity extends BaseActivity {
    public static final String EXTRA_ID = "id";
    private static final String[] CATEGORIE_BASE = {
            "Materiale didattico", "Gite e uscite", "Regali", "Feste", "Fotocopie", "Altro"};

    private String id;
    private EditText campoDescrizione;
    private MaterialAutoCompleteTextView campoCategoria;
    private EditText campoData;
    private EditText campoImporto;

    /** Stato del giustificativo mostrato a schermo. */
    private String url = "";
    private String locale;
    private String mime;
    /** True se l'utente ha cambiato o rimosso l'allegato in questa schermata. */
    private boolean allegatoModificato;
    private boolean salvato;

    private final ActivityResultLauncher<Uri> fotocamera =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), riuscito -> {
                if (Boolean.TRUE.equals(riuscito)) {
                    acquisisci(Giustificativi.uriPer(this, Giustificativi.fileFotoTemporaneo(this)));
                }
            });

    private final ActivityResultLauncher<String[]> selettore =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    acquisisci(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_spesa_edit);
        applicaMargini(this);
        id = getIntent().getStringExtra(EXTRA_ID);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(id == null ? R.string.spesa_nuova : R.string.spesa_modifica);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        if (id != null) {
            toolbar.inflateMenu(R.menu.menu_edit);
            toolbar.setOnMenuItemClickListener(item -> {
                confermaElimina();
                return true;
            });
            View elimina = findViewById(R.id.elimina);
            elimina.setVisibility(View.VISIBLE);
            elimina.setOnClickListener(v -> confermaElimina());
        }

        campoDescrizione = findViewById(R.id.campo_descrizione);
        campoCategoria = findViewById(R.id.campo_categoria);
        campoData = findViewById(R.id.campo_data);
        campoImporto = findViewById(R.id.campo_importo);
        campoCategoria.setSimpleItems(CATEGORIE_BASE);

        findViewById(R.id.salva).setOnClickListener(v -> salva());
        findViewById(R.id.giustificativo_foto).setOnClickListener(v -> scattaFoto());
        findViewById(R.id.giustificativo_file).setOnClickListener(v -> {
            try {
                selettore.launch(new String[]{"image/*", "application/pdf"});
            } catch (ActivityNotFoundException e) {
                avviso(R.string.nessuna_app);
            }
        });
        findViewById(R.id.giustificativo_apri).setOnClickListener(v -> apriGiustificativo());
        findViewById(R.id.giustificativo_rimuovi).setOnClickListener(v -> {
            scartaAllegatoNuovo();
            url = "";
            locale = null;
            mime = null;
            allegatoModificato = true;
            mostraGiustificativo();
        });

        boolean primaApertura = savedInstanceState == null;
        if (!primaApertura) {
            url = savedInstanceState.getString("url", "");
            locale = savedInstanceState.getString("locale");
            mime = savedInstanceState.getString("mime");
            allegatoModificato = savedInstanceState.getBoolean("modificato");
            collegaData(campoData, savedInstanceState.getString("data", Date.oggi()));
            mostraGiustificativo();
        } else if (id == null) {
            collegaData(campoData, Date.oggi());
            mostraGiustificativo();
        }
        FondocassaApp app = app();
        app.io().execute(() -> {
            Set<String> categorie = new LinkedHashSet<>(app.db().spese().categorie());
            for (String c : CATEGORIE_BASE) {
                categorie.add(c);
            }
            Spesa spesa = id == null ? null : app.db().spese().get(id);
            runOnUiThread(() -> {
                campoCategoria.setSimpleItems(categorie.toArray(new String[0]));
                if (primaApertura && spesa != null) {
                    compila(spesa);
                }
            });
        });
    }

    private void compila(Spesa spesa) {
        campoDescrizione.setText(spesa.descrizione);
        campoCategoria.setText(spesa.categoria, false);
        collegaData(campoData, spesa.data);
        campoImporto.setText(Money.formatInput(spesa.importo));
        url = spesa.urlGiustificativo == null ? "" : spesa.urlGiustificativo;
        locale = spesa.giustificativoLocale;
        mime = spesa.giustificativoMime;
        mostraGiustificativo();
    }

    private void mostraGiustificativo() {
        TextView stato = findViewById(R.id.stato_giustificativo);
        boolean presente = !url.isEmpty() || locale != null;
        stato.setText(!url.isEmpty() ? R.string.giustificativo_drive
                : locale != null ? R.string.giustificativo_locale : R.string.giustificativo_nessuno);
        findViewById(R.id.azioni_giustificativo).setVisibility(presente ? View.VISIBLE : View.GONE);
    }

    private void scattaFoto() {
        try {
            fotocamera.launch(Giustificativi.uriPer(this, Giustificativi.fileFotoTemporaneo(this)));
        } catch (ActivityNotFoundException e) {
            avviso(R.string.nessuna_app);
        }
    }

    private void acquisisci(Uri origine) {
        FondocassaApp app = app();
        app.io().execute(() -> {
            try {
                Giustificativi.Acquisito acquisito = Giustificativi.acquisisci(getApplicationContext(), origine);
                runOnUiThread(() -> {
                    scartaAllegatoNuovo();
                    url = "";
                    locale = acquisito.file.getAbsolutePath();
                    mime = acquisito.mime;
                    allegatoModificato = true;
                    mostraGiustificativo();
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> avviso(R.string.giustificativo_errore));
            }
        });
    }

    /** Cancella dal disco un allegato scelto in questa schermata e poi sostituito o rimosso. */
    private void scartaAllegatoNuovo() {
        if (allegatoModificato && locale != null) {
            Repository.cancellaFile(locale);
        }
    }

    private void apriGiustificativo() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (!url.isEmpty()) {
            intent.setData(Uri.parse(url));
        } else if (locale != null) {
            intent.setDataAndType(Giustificativi.uriPer(this, new File(locale)), mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            avviso(R.string.nessuna_app);
        }
    }

    private void salva() {
        TextInputLayout layoutDescrizione = findViewById(R.id.layout_descrizione);
        TextInputLayout layoutImporto = findViewById(R.id.layout_importo);
        layoutDescrizione.setError(null);
        layoutImporto.setError(null);
        String descrizione = testo(campoDescrizione);
        if (descrizione.isEmpty()) {
            layoutDescrizione.setError(getString(R.string.errore_campo_obbligatorio));
            return;
        }
        long importo;
        try {
            importo = Money.parse(testo(campoImporto));
        } catch (IllegalArgumentException e) {
            importo = 0;
        }
        if (importo <= 0) {
            layoutImporto.setError(getString(R.string.errore_importo));
            return;
        }
        long centesimi = importo;
        String categoria = testo(campoCategoria);
        String data = (String) campoData.getTag();
        String nuovoUrl = url;
        String nuovoLocale = locale;
        String nuovoMime = mime;
        boolean modificato = allegatoModificato;
        FondocassaApp app = app();
        app.io().execute(() -> {
            // Rilegge il record: nel frattempo la sincronizzazione potrebbe aver caricato l'allegato.
            Spesa spesa = id == null ? null : app.db().spese().get(id);
            if (spesa == null) {
                spesa = new Spesa();
            }
            spesa.descrizione = descrizione;
            spesa.categoria = categoria;
            spesa.data = data;
            spesa.importo = centesimi;
            if (modificato) {
                if (spesa.giustificativoLocale != null && !spesa.giustificativoLocale.equals(nuovoLocale)) {
                    Repository.cancellaFile(spesa.giustificativoLocale);
                }
                spesa.urlGiustificativo = nuovoUrl;
                spesa.giustificativoLocale = nuovoLocale;
                spesa.giustificativoMime = nuovoMime;
            }
            app.repository().salvaSpesa(spesa);
        });
        salvato = true;
        finish();
    }

    private void confermaElimina() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.conferma_elimina_titolo)
                .setMessage(R.string.conferma_elimina_foglio)
                .setPositiveButton(R.string.azione_elimina, (d, w) -> {
                    FondocassaApp app = app();
                    app.io().execute(() -> app.repository().eliminaSpesa(id));
                    finish();
                })
                .setNegativeButton(R.string.azione_annulla, null)
                .show();
    }

    private void avviso(int testo) {
        Snackbar.make(findViewById(android.R.id.content), testo, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("url", url);
        outState.putString("locale", locale);
        outState.putString("mime", mime);
        outState.putBoolean("modificato", allegatoModificato);
        if (campoData.getTag() != null) {
            outState.putString("data", (String) campoData.getTag());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Uscita senza salvare: l'allegato appena acquisito non serve più.
        if (isFinishing() && !salvato) {
            scartaAllegatoNuovo();
        }
    }
}
