package it.fondocassa.ui;

import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.Quota;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;

/** Finestra per definire, correggere o eliminare una quota richiesta a tutti gli alunni. */
public final class QuotaDialog {
    private QuotaDialog() {
    }

    /** @param idQuota null per una nuova quota */
    public static void mostra(Fragment fragment, @Nullable String idQuota) {
        FondocassaApp app = (FondocassaApp) fragment.requireActivity().getApplication();
        if (idQuota == null) {
            Quota nuova = new Quota();
            nuova.data = Date.oggi();
            apri(fragment, app, nuova, true);
            return;
        }
        app.io().execute(() -> {
            Quota quota = app.db().quote().get(idQuota);
            if (quota != null) {
                fragment.requireActivity().runOnUiThread(() -> {
                    if (fragment.isAdded()) {
                        apri(fragment, app, quota, false);
                    }
                });
            }
        });
    }

    private static void apri(Fragment fragment, FondocassaApp app, Quota quota, boolean nuova) {
        View vista = fragment.getLayoutInflater().inflate(R.layout.dialog_quota, null);
        EditText descrizione = vista.findViewById(R.id.campo_descrizione);
        EditText importo = vista.findViewById(R.id.campo_importo);
        EditText data = vista.findViewById(R.id.campo_data);
        descrizione.setText(nuova ? fragment.getString(R.string.quota_descrizione_esempio) : quota.descrizione);
        if (!nuova) {
            importo.setText(Money.formatInput(quota.importo));
        }
        BaseActivity.collegaData(data, quota.data);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(nuova ? R.string.quota_nuova : R.string.quota_modifica)
                .setView(vista)
                .setPositiveButton(R.string.azione_salva, null)
                .setNegativeButton(R.string.azione_annulla, null);
        if (!nuova) {
            builder.setNeutralButton(R.string.azione_elimina,
                    (d, w) -> app.io().execute(() -> app.repository().eliminaQuota(quota.id)));
        }
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String testo = BaseActivity.testo(descrizione);
            if (testo.isEmpty()) {
                descrizione.setError(fragment.getString(R.string.errore_campo_obbligatorio));
                return;
            }
            long centesimi;
            try {
                centesimi = Money.parse(BaseActivity.testo(importo));
            } catch (IllegalArgumentException e) {
                centesimi = 0;
            }
            if (centesimi <= 0) {
                importo.setError(fragment.getString(R.string.errore_importo));
                return;
            }
            quota.descrizione = testo;
            quota.importo = centesimi;
            quota.data = (String) data.getTag();
            app.io().execute(() -> app.repository().salvaQuota(quota));
            dialog.dismiss();
        });
    }
}
