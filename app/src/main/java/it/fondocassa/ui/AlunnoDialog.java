package it.fondocassa.ui;

import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.Alunno;

/** Finestra per aggiungere, rinominare, disattivare o eliminare un alunno. */
public final class AlunnoDialog {
    private AlunnoDialog() {
    }

    /** @param idAlunno null per un nuovo alunno */
    public static void mostra(Fragment fragment, @Nullable String idAlunno) {
        FondocassaApp app = (FondocassaApp) fragment.requireActivity().getApplication();
        if (idAlunno == null) {
            apri(fragment, app, new Alunno(), true);
            return;
        }
        app.io().execute(() -> {
            Alunno alunno = app.db().alunni().get(idAlunno);
            if (alunno != null) {
                fragment.requireActivity().runOnUiThread(() -> {
                    if (fragment.isAdded()) {
                        apri(fragment, app, alunno, false);
                    }
                });
            }
        });
    }

    private static void apri(Fragment fragment, FondocassaApp app, Alunno alunno, boolean nuovo) {
        View vista = fragment.getLayoutInflater().inflate(R.layout.dialog_alunno, null);
        EditText nome = vista.findViewById(R.id.campo_nome);
        MaterialSwitch attivo = vista.findViewById(R.id.attivo);
        EditText note = vista.findViewById(R.id.campo_note);
        nome.setText(alunno.nome);
        note.setText(alunno.note);
        attivo.setChecked(alunno.attivo);
        attivo.setVisibility(nuovo ? View.GONE : View.VISIBLE);
        View ancora = fragment.requireView();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(nuovo ? R.string.alunno_nuovo : R.string.alunno_modifica)
                .setView(vista)
                .setPositiveButton(R.string.azione_salva, null)
                .setNegativeButton(R.string.azione_annulla, null);
        if (!nuovo) {
            builder.setNeutralButton(R.string.azione_elimina, (d, w) -> app.io().execute(() -> {
                if (!app.repository().eliminaAlunno(alunno.id)) {
                    ancora.post(() -> Snackbar.make(ancora, R.string.alunno_ha_entrate, Snackbar.LENGTH_LONG).show());
                }
            }));
        }
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        nome.requestFocus();
        // Il pulsante Salva non chiude la finestra se il nome è vuoto.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String valore = BaseActivity.testo(nome);
            if (valore.isEmpty()) {
                nome.setError(fragment.getString(R.string.errore_campo_obbligatorio));
                return;
            }
            alunno.nome = valore;
            alunno.note = BaseActivity.testo(note);
            alunno.attivo = attivo.isChecked();
            app.io().execute(() -> app.repository().salvaAlunno(alunno));
            dialog.dismiss();
        });
    }
}
