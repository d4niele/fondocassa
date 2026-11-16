package it.fondocassa.ui;

import java.util.ArrayList;
import java.util.List;

import it.fondocassa.R;
import it.fondocassa.data.Quota;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;

/** Quote uguali per tutti: ogni voce aumenta il dovuto di ciascun alunno. */
public class QuoteFragment extends ListaFragment {
    @Override
    protected int messaggioVuoto() {
        return R.string.vuoto_quote;
    }

    @Override
    protected void osserva() {
        app().db().quote().observeAll().observe(getViewLifecycleOwner(), quote -> {
            List<RigaAdapter.Riga> righe = new ArrayList<>();
            for (Quota q : quote) {
                righe.add(new RigaAdapter.Riga(q.id, q.descrizione, Date.mostra(q.data),
                        Money.format(q.importo),
                        q.synced ? getString(R.string.quota_a_testa) : getString(R.string.in_attesa_sync), 0));
            }
            mostra(righe);
        });
    }

    @Override
    protected void alTocco(RigaAdapter.Riga riga) {
        QuotaDialog.mostra(this, riga.id);
    }

    @Override
    protected void alAggiungi() {
        QuotaDialog.mostra(this, null);
    }
}
