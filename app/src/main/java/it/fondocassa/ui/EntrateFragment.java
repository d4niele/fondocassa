package it.fondocassa.ui;

import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import it.fondocassa.R;
import it.fondocassa.data.EntrataConAlunno;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;

/** Registro cronologico delle quote incassate. */
public class EntrateFragment extends ListaFragment {
    @Override
    protected int messaggioVuoto() {
        return R.string.vuoto_entrate;
    }

    @Override
    protected void osserva() {
        app().db().entrate().observeAll().observe(getViewLifecycleOwner(), entrate -> {
            List<RigaAdapter.Riga> righe = new ArrayList<>();
            for (EntrataConAlunno e : entrate) {
                String nome = e.nomeAlunno != null ? e.nomeAlunno : getString(R.string.alunno_sconosciuto);
                String sottotitolo = Date.mostra(e.entrata.data) + " · " + e.entrata.metodo;
                if (e.entrata.note != null && !e.entrata.note.isEmpty()) {
                    sottotitolo += " · " + e.entrata.note;
                }
                righe.add(new RigaAdapter.Riga(e.entrata.id, nome, sottotitolo, Money.format(e.entrata.importo),
                        e.entrata.synced ? "" : getString(R.string.in_attesa_sync), 0));
            }
            mostra(righe);
        });
    }

    @Override
    protected void alTocco(RigaAdapter.Riga riga) {
        startActivity(new Intent(requireContext(), EntrataEditActivity.class)
                .putExtra(EntrataEditActivity.EXTRA_ID, riga.id));
    }

    @Override
    protected void alAggiungi() {
        startActivity(new Intent(requireContext(), EntrataEditActivity.class));
    }
}
