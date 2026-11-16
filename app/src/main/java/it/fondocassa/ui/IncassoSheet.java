package it.fondocassa.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.Alunno;
import it.fondocassa.data.AlunnoConSaldo;
import it.fondocassa.data.Entrata;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;
import it.fondocassa.util.StatoPagamento;

/**
 * Incasso rapido: un tocco sull'alunno apre questo pannello con l'importo residuo già
 * compilato, il metodo a scelta singola e la data di oggi. Basta toccare "Registra quota".
 */
public class IncassoSheet extends BottomSheetDialogFragment {
    private static final String ARG_ALUNNO = "alunno_id";

    public static IncassoSheet per(String idAlunno) {
        IncassoSheet sheet = new IncassoSheet();
        Bundle args = new Bundle();
        args.putString(ARG_ALUNNO, idAlunno);
        sheet.setArguments(args);
        return sheet;
    }

    private EditText importo;
    private TextInputLayout layoutImporto;
    private ChipGroup metodi;
    private EditText data;
    private EditText note;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_incasso, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FondocassaApp app = (FondocassaApp) requireActivity().getApplication();
        String idAlunno = requireArguments().getString(ARG_ALUNNO);
        importo = view.findViewById(R.id.campo_importo);
        layoutImporto = view.findViewById(R.id.layout_importo);
        metodi = view.findViewById(R.id.metodi);
        data = view.findViewById(R.id.campo_data);
        note = view.findViewById(R.id.campo_note);

        for (String metodo : Entrata.METODI) {
            Chip chip = nuovoChip(metodo, true);
            metodi.addView(chip);
            if (savedInstanceState == null && metodo.equals(Entrata.METODI[0])) {
                chip.setChecked(true);
            }
        }
        if (savedInstanceState == null) {
            BaseActivity.collegaData(data, Date.oggi());
        } else {
            BaseActivity.collegaData(data, savedInstanceState.getString("data", Date.oggi()));
        }

        view.findViewById(R.id.registra).setOnClickListener(v -> registra(app, idAlunno));
        view.findViewById(R.id.modifica_alunno).setOnClickListener(v -> {
            Fragment genitore = requireParentFragment();
            dismiss();
            AlunnoDialog.mostra(genitore, idAlunno);
        });

        boolean primaApertura = savedInstanceState == null;
        app.io().execute(() -> {
            AlunnoConSaldo trovato = null;
            for (AlunnoConSaldo a : app.db().alunni().listConSaldo()) {
                if (a.alunno.id.equals(idAlunno)) {
                    trovato = a;
                    break;
                }
            }
            AlunnoConSaldo alunno = trovato;
            long quota = app.db().quote().totale();
            view.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (alunno == null) {
                    dismiss();
                    return;
                }
                compila(view, alunno.alunno, alunno.totaleVersato, quota, primaApertura);
            });
        });
    }

    private void compila(View view, Alunno alunno, long versato, long quota, boolean primaApertura) {
        ((TextView) view.findViewById(R.id.nome)).setText(alunno.nome);
        ((TextView) view.findViewById(R.id.situazione)).setText(quota > 0
                ? getString(R.string.incasso_situazione, Money.format(versato), Money.format(quota))
                : getString(R.string.incasso_situazione_senza_quota, Money.format(versato)));

        long residuo = StatoPagamento.residuo(versato, quota);
        ChipGroup rapidi = view.findViewById(R.id.importi_rapidi);
        rapidi.removeAllViews();
        if (residuo > 0 && residuo != quota) {
            rapidi.addView(chipImporto(getString(R.string.incasso_residuo, Money.format(residuo)), residuo));
        }
        if (quota > 0) {
            rapidi.addView(chipImporto(getString(R.string.incasso_quota_intera, Money.format(quota)), quota));
        }
        if (primaApertura && residuo > 0) {
            importo.setText(Money.formatInput(residuo));
        }
        if (primaApertura && residuo == 0) {
            importo.requestFocus();
        }
    }

    private Chip chipImporto(String testo, long centesimi) {
        Chip chip = nuovoChip(testo, false);
        chip.setOnClickListener(v -> {
            importo.setText(Money.formatInput(centesimi));
            layoutImporto.setError(null);
        });
        return chip;
    }

    private Chip nuovoChip(String testo, boolean selezionabile) {
        Chip chip = new Chip(requireContext());
        chip.setText(testo);
        chip.setCheckable(selezionabile);
        chip.setId(View.generateViewId());
        return chip;
    }

    private void registra(FondocassaApp app, String idAlunno) {
        long centesimi;
        try {
            centesimi = Money.parse(BaseActivity.testo(importo));
        } catch (IllegalArgumentException e) {
            centesimi = 0;
        }
        if (centesimi <= 0) {
            layoutImporto.setError(getString(R.string.errore_importo));
            return;
        }
        Entrata entrata = new Entrata();
        entrata.alunnoId = idAlunno;
        entrata.importo = centesimi;
        entrata.data = (String) data.getTag();
        entrata.note = BaseActivity.testo(note);
        Chip scelto = metodi.findViewById(metodi.getCheckedChipId());
        entrata.metodo = scelto != null ? scelto.getText().toString() : Entrata.METODI[0];

        String nome = ((TextView) requireView().findViewById(R.id.nome)).getText().toString();
        View ancora = requireParentFragment().requireView();
        String messaggio = getString(R.string.incasso_fatto, Money.format(centesimi), nome);
        app.io().execute(() -> {
            app.repository().salvaEntrata(entrata);
            ancora.post(() -> Snackbar.make(ancora, messaggio, Snackbar.LENGTH_LONG)
                    .setAction(R.string.azione_annulla,
                            v -> app.io().execute(() -> app.repository().eliminaEntrata(entrata.id)))
                    .show());
        });
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Aperto per intero: il pulsante "Registra quota" deve essere subito visibile.
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> comportamento = ((BottomSheetDialog) getDialog()).getBehavior();
            comportamento.setState(BottomSheetBehavior.STATE_EXPANDED);
            comportamento.setSkipCollapsed(true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (data != null && data.getTag() != null) {
            outState.putString("data", (String) data.getTag());
        }
    }
}
