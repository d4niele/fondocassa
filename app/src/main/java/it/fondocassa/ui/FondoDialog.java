package it.fondocassa.ui;

import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.Fondo;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;

/** Finestra per impostare il fondo iniziale, cioè l'avanzo degli anni passati. */
public final class FondoDialog {
    private FondoDialog() {
    }

    /** @param attuale il fondo già impostato, o null */
    public static void mostra(Fragment fragment, @Nullable Fondo attuale) {
        FondocassaApp app = (FondocassaApp) fragment.requireActivity().getApplication();
        View vista = fragment.getLayoutInflater().inflate(R.layout.dialog_fondo, null);
        EditText descrizione = vista.findViewById(R.id.campo_descrizione);
        EditText importo = vista.findViewById(R.id.campo_importo);
        EditText data = vista.findViewById(R.id.campo_data);
        boolean nuovo = attuale == null || (attuale.importo == 0 && attuale.descrizione.isEmpty());
        descrizione.setText(nuovo ? fragment.getString(R.string.fondo_descrizione_esempio) : attuale.descrizione);
        if (!nuovo) {
            importo.setText(Money.formatInput(attuale.importo));
        }
        BaseActivity.collegaData(data, nuovo || attuale.data.isEmpty() ? Date.oggi() : attuale.data);

        AlertDialog dialog = new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.fondo_iniziale)
                .setView(vista)
                .setPositiveButton(R.string.azione_salva, null)
                .setNegativeButton(R.string.azione_annulla, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String testoImporto = BaseActivity.testo(importo);
            long centesimi = 0;
            if (!testoImporto.isEmpty()) {
                try {
                    centesimi = Money.parse(testoImporto);
                } catch (IllegalArgumentException e) {
                    importo.setError(fragment.getString(R.string.errore_importo));
                    return;
                }
            }
            long valore = centesimi;
            String testo = BaseActivity.testo(descrizione);
            String giorno = (String) data.getTag();
            app.io().execute(() -> app.repository().salvaFondoIniziale(valore, testo, giorno));
            dialog.dismiss();
        });
    }
}
