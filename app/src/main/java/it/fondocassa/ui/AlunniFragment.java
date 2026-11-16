package it.fondocassa.ui;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import it.fondocassa.R;
import it.fondocassa.data.AlunnoConSaldo;
import it.fondocassa.util.Money;
import it.fondocassa.util.StatoPagamento;

/** Elenco della classe: un tocco sull'alunno apre l'incasso rapido della quota. */
public class AlunniFragment extends ListaFragment {
    private List<AlunnoConSaldo> alunni = new ArrayList<>();
    private long dovuto;

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
}
