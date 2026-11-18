package it.fondocassa.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.data.AlunnoConSaldo;
import it.fondocassa.data.Fondo;
import it.fondocassa.util.Bilancio;
import it.fondocassa.util.Money;

/**
 * Cruscotto: quanto c'è davvero in cassa, quanto ci sarebbe se tutti avessero pagato,
 * e l'andamento delle quote (previsto, versato, da versare).
 */
public class RiepilogoFragment extends Fragment {
    private long entrate;
    private long uscite;
    private long dovutoPerAlunno;
    private Fondo fondo;
    private final int[] pendenti = new int[5];
    private List<AlunnoConSaldo> alunni = new ArrayList<>();

    private FondocassaApp app() {
        return (FondocassaApp) requireActivity().getApplication();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_riepilogo, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FondocassaApp app = app();
        app.db().entrate().observeTotale().observe(getViewLifecycleOwner(), v -> {
            entrate = v == null ? 0 : v;
            aggiorna();
        });
        app.db().spese().observeTotale().observe(getViewLifecycleOwner(), v -> {
            uscite = v == null ? 0 : v;
            aggiorna();
        });
        app.db().quote().observeTotale().observe(getViewLifecycleOwner(), v -> {
            dovutoPerAlunno = v == null ? 0 : v;
            aggiorna();
        });
        app.db().alunni().observeConSaldo().observe(getViewLifecycleOwner(), v -> {
            alunni = v;
            aggiorna();
        });
        app.db().fondi().observe(Fondo.ID_INIZIALE).observe(getViewLifecycleOwner(), v -> {
            fondo = v;
            aggiorna();
        });
        app.db().fondi().observePendenti().observe(getViewLifecycleOwner(), v -> pendenti(4, v));
        app.db().alunni().observePendenti().observe(getViewLifecycleOwner(), v -> pendenti(0, v));
        app.db().quote().observePendenti().observe(getViewLifecycleOwner(), v -> pendenti(1, v));
        app.db().entrate().observePendenti().observe(getViewLifecycleOwner(), v -> pendenti(2, v));
        app.db().spese().observePendenti().observe(getViewLifecycleOwner(), v -> pendenti(3, v));
        view.findViewById(R.id.bottone_sincronizza)
                .setOnClickListener(v -> ((MainActivity) requireActivity()).sincronizza());
    }

    @Override
    public void onResume() {
        super.onResume();
        aggiorna();
    }

    private void pendenti(int indice, Integer valore) {
        pendenti[indice] = valore == null ? 0 : valore;
        aggiorna();
    }

    /** Ricalcola e ridisegna il cruscotto. */
    public void aggiorna() {
        View v = getView();
        if (v == null) {
            return;
        }
        long fondoIniziale = fondo == null ? 0 : fondo.importo;
        Bilancio b = Bilancio.calcola(alunni, dovutoPerAlunno, entrate, uscite, fondoIniziale);
        TextView fondoIncluso = v.findViewById(R.id.testo_fondo_incluso);
        fondoIncluso.setVisibility(fondoIniziale != 0 ? View.VISIBLE : View.GONE);
        fondoIncluso.setText(getString(R.string.fondo_incluso, Money.format(fondoIniziale)));
        testo(v, R.id.testo_cassa_reale, Money.format(b.cassaReale));
        testo(v, R.id.testo_cassa_virtuale, Money.format(b.cassaVirtuale));
        testo(v, R.id.testo_previsto, Money.format(b.previsto));
        testo(v, R.id.testo_versato, Money.format(b.versato));
        testo(v, R.id.testo_da_versare, Money.format(b.daVersare));
        testo(v, R.id.testo_uscite, Money.format(b.spese));
        testo(v, R.id.testo_quote_dettaglio, dovutoPerAlunno > 0
                ? getString(R.string.quote_dettaglio, Money.format(dovutoPerAlunno), b.alunniAttivi)
                : getString(R.string.quote_nessuna));
        testo(v, R.id.testo_in_regola,
                getString(R.string.alunni_in_regola_valore, b.alunniInRegola, b.alunniAttivi));

        LinearProgressIndicator avanzamento = v.findViewById(R.id.avanzamento);
        long incassatoSulPrevisto = b.previsto - b.daVersare;
        avanzamento.setProgress(b.previsto > 0 ? (int) (incassatoSulPrevisto * 100 / b.previsto) : 0);

        int daSincronizzare = 0;
        for (int p : pendenti) {
            daSincronizzare += p;
        }
        testo(v, R.id.testo_sync, getString(R.string.stato_sync, ultimaSync()));
        testo(v, R.id.bottone_sincronizza, daSincronizzare > 0
                ? getString(R.string.sincronizza_record, daSincronizzare)
                : getString(R.string.azione_sincronizza));
    }

    private static void testo(View radice, int id, String valore) {
        ((TextView) radice.findViewById(id)).setText(valore);
    }

    private String ultimaSync() {
        String ts = app().settings().ultimaSync();
        if (ts.isEmpty()) {
            return getString(R.string.mai);
        }
        try {
            return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .format(Instant.parse(ts).atZone(ZoneId.systemDefault()));
        } catch (DateTimeParseException e) {
            return ts;
        }
    }
}
