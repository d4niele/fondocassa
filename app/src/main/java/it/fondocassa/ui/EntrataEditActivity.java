package it.fondocassa.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.Alunno;
import it.fondocassa.data.Entrata;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;

/** Modulo completo per inserire o correggere una quota (l'incasso rapido è in {@link IncassoSheet}). */
public class EntrataEditActivity extends BaseActivity {
    public static final String EXTRA_ID = "id";

    private String id;
    private final List<Alunno> alunni = new ArrayList<>();
    private String alunnoScelto;

    private MaterialAutoCompleteTextView campoAlunno;
    private MaterialAutoCompleteTextView campoMetodo;
    private EditText campoData;
    private EditText campoImporto;
    private EditText campoNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entrata_edit);
        applicaMargini(this);
        id = getIntent().getStringExtra(EXTRA_ID);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(id == null ? R.string.entrata_nuova : R.string.entrata_modifica);
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

        campoAlunno = findViewById(R.id.campo_alunno);
        campoMetodo = findViewById(R.id.campo_metodo);
        campoData = findViewById(R.id.campo_data);
        campoImporto = findViewById(R.id.campo_importo);
        campoNote = findViewById(R.id.campo_note);
        campoMetodo.setSimpleItems(Entrata.METODI);
        campoAlunno.setOnItemClickListener((parent, view, position, rowId) ->
                alunnoScelto = alunni.get(position).id);
        findViewById(R.id.salva).setOnClickListener(v -> salva());

        boolean primaApertura = savedInstanceState == null;
        if (!primaApertura) {
            alunnoScelto = savedInstanceState.getString("alunno");
            collegaData(campoData, savedInstanceState.getString("data", Date.oggi()));
        }
        FondocassaApp app = app();
        app.io().execute(() -> {
            List<Alunno> elenco = app.db().alunni().list();
            Entrata entrata = id == null ? null : app.db().entrate().get(id);
            runOnUiThread(() -> compila(elenco, entrata, primaApertura));
        });
    }

    private void compila(List<Alunno> elenco, Entrata entrata, boolean primaApertura) {
        alunni.clear();
        alunni.addAll(elenco);
        List<String> nomi = new ArrayList<>();
        for (Alunno a : alunni) {
            nomi.add(a.nome);
        }
        campoAlunno.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, nomi));
        if (alunni.isEmpty()) {
            ((TextInputLayout) findViewById(R.id.layout_alunno)).setError(getString(R.string.serve_alunno));
        }
        if (!primaApertura) {
            return; // i campi sono già stati ripristinati dal sistema
        }
        if (entrata == null) {
            collegaData(campoData, Date.oggi());
            campoMetodo.setText(Entrata.METODI[0], false);
            return;
        }
        alunnoScelto = entrata.alunnoId;
        for (Alunno a : alunni) {
            if (a.id.equals(entrata.alunnoId)) {
                campoAlunno.setText(a.nome, false);
            }
        }
        collegaData(campoData, entrata.data);
        campoImporto.setText(Money.formatInput(entrata.importo));
        campoMetodo.setText(entrata.metodo, false);
        campoNote.setText(entrata.note);
    }

    private void salva() {
        TextInputLayout layoutAlunno = findViewById(R.id.layout_alunno);
        TextInputLayout layoutImporto = findViewById(R.id.layout_importo);
        layoutAlunno.setError(null);
        layoutImporto.setError(null);
        if (alunnoScelto == null) {
            layoutAlunno.setError(getString(R.string.errore_campo_obbligatorio));
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
        String data = (String) campoData.getTag();
        String metodo = testo(campoMetodo);
        String note = testo(campoNote);
        FondocassaApp app = app();
        app.io().execute(() -> {
            Entrata entrata = id == null ? null : app.db().entrate().get(id);
            if (entrata == null) {
                entrata = new Entrata();
            }
            entrata.alunnoId = alunnoScelto;
            entrata.data = data;
            entrata.importo = centesimi;
            entrata.metodo = metodo.isEmpty() ? Entrata.METODI[0] : metodo;
            entrata.note = note;
            app.repository().salvaEntrata(entrata);
        });
        finish();
    }

    private void confermaElimina() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.conferma_elimina_titolo)
                .setMessage(R.string.conferma_elimina_foglio)
                .setPositiveButton(R.string.azione_elimina, (d, w) -> {
                    FondocassaApp app = app();
                    app.io().execute(() -> app.repository().eliminaEntrata(id));
                    finish();
                })
                .setNegativeButton(R.string.azione_annulla, null)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("alunno", alunnoScelto);
        if (campoData.getTag() != null) {
            outState.putString("data", (String) campoData.getTag());
        }
    }
}
