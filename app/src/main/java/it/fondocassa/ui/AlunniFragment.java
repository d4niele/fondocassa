package it.fondocassa.ui;

import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.Alunno;
import it.fondocassa.data.AlunnoConSaldo;
import it.fondocassa.util.ImportAlunni;
import it.fondocassa.util.Money;
import it.fondocassa.util.StatoPagamento;

/** Elenco della classe: un tocco sull'alunno apre l'incasso rapido della quota. */
public class AlunniFragment extends ListaFragment {
    private List<AlunnoConSaldo> alunni = new ArrayList<>();
    private long dovuto;

    private static final int MAX_BYTE_IMPORT = 1024 * 1024;
    /** Campo di testo della finestra di importazione, quando è aperta. */
    private EditText campoImporta;
    private final ActivityResultLauncher<String[]> selettoreFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    caricaFile(uri);
                }
            });

    @Override
    protected int messaggioVuoto() {
        return R.string.vuoto_alunni;
    }

    @Override
    protected void osserva() {
        app().db().alunni().observeConSaldo().observe(getViewLifecycleOwner(), elenco -> {
            alunni = elenco;
            aggiorna();
        });
        app().db().quote().observeTotale().observe(getViewLifecycleOwner(), totale -> {
            dovuto = totale == null ? 0 : totale;
            aggiorna();
        });
    }

    private void aggiorna() {
        int ok = ContextCompat.getColor(requireContext(), R.color.stato_ok);
        int attenzione = ContextCompat.getColor(requireContext(), R.color.stato_attenzione);
        List<RigaAdapter.Riga> righe = new ArrayList<>();
        for (AlunnoConSaldo a : alunni) {
            String sottotitolo = "";
            String etichetta;
            int colore;
            if (!a.alunno.attivo) {
                etichetta = getString(R.string.non_attivo);
                colore = 0;
            } else {
                StatoPagamento stato = StatoPagamento.calcola(a.totaleVersato, dovuto);
                etichetta = stato.etichetta;
                colore = stato == StatoPagamento.IN_REGOLA ? ok : attenzione;
                long residuo = StatoPagamento.residuo(a.totaleVersato, dovuto);
                if (residuo > 0) {
                    sottotitolo = getString(R.string.incasso_residuo, Money.format(residuo));
                }
            }
            righe.add(new RigaAdapter.Riga(a.alunno.id, a.alunno.nome, sottotitolo,
                    Money.format(a.totaleVersato), etichetta, colore));
        }
        mostra(righe);
    }

    @Override
    protected void alTocco(RigaAdapter.Riga riga) {
        IncassoSheet.per(riga.id).show(getChildFragmentManager(), "incasso");
    }

    @Override
    protected void alAggiungi() {
        AlunnoDialog.mostra(this, null);
    }

    // --- Importazione dell'elenco (solo a lista vuota) ---

    @Override
    protected int azioneVuoto() {
        return R.string.importa_alunni;
    }

    @Override
    protected void alAzioneVuoto() {
        View vista = getLayoutInflater().inflate(R.layout.dialog_importa, null);
        campoImporta = vista.findViewById(R.id.campo_testo);
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.importa_alunni)
                .setView(vista)
                .setPositiveButton(R.string.importa_conferma, null)
                .setNeutralButton(R.string.giustificativo_file, null)
                .setNegativeButton(R.string.azione_annulla, null)
                .create();
        dialog.setOnDismissListener(d -> campoImporta = null);
        dialog.show();
        // I pulsanti non chiudono la finestra: si può scegliere un file e correggere il testo.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            try {
                selettoreFile.launch(new String[]{"text/*", "application/json", "application/vnd.ms-excel", "*/*"});
            } catch (ActivityNotFoundException e) {
                Snackbar.make(requireView(), R.string.nessuna_app, Snackbar.LENGTH_LONG).show();
            }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String errore = importa(BaseActivity.testo(campoImporta));
            if (errore == null) {
                dialog.dismiss();
            } else {
                ((TextInputLayout) vista.findViewById(R.id.layout_testo)).setError(errore);
            }
        });
    }

    /**
     * Importa l'elenco da testo CSV o JSON.
     *
     * @return null se l'importazione è partita, altrimenti il messaggio di errore
     */
    String importa(String testo) {
        List<ImportAlunni.Riga> righe;
        try {
            righe = ImportAlunni.leggi(testo);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        FondocassaApp app = app();
        View ancora = requireView();
        String messaggio = getString(R.string.importa_fatto, righe.size());
        app.io().execute(() -> {
            for (ImportAlunni.Riga riga : righe) {
                Alunno alunno = new Alunno();
                alunno.nome = riga.nome;
                alunno.note = riga.note;
                app.repository().salvaAlunno(alunno);
            }
            ancora.post(() -> Snackbar.make(ancora, messaggio, Snackbar.LENGTH_LONG).show());
        });
        return null;
    }

    private void caricaFile(Uri uri) {
        FondocassaApp app = app();
        android.content.Context context = requireContext().getApplicationContext();
        app.io().execute(() -> {
            String testo;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("File non accessibile");
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] blocco = new byte[8192];
                int letti;
                while ((letti = in.read(blocco)) != -1 && buffer.size() < MAX_BYTE_IMPORT) {
                    buffer.write(blocco, 0, letti);
                }
                testo = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                testo = null;
            }
            String letto = testo;
            requireActivity().runOnUiThread(() -> {
                if (campoImporta == null) {
                    return;
                }
                if (letto == null) {
                    Snackbar.make(campoImporta, R.string.giustificativo_errore, Snackbar.LENGTH_LONG).show();
                } else {
                    campoImporta.setText(letto);
                }
            });
        });
    }
}
