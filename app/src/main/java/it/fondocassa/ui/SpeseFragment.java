package it.fondocassa.ui;

import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import it.fondocassa.R;
import it.fondocassa.data.Spesa;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;

/** Registro delle uscite con i relativi giustificativi. */
public class SpeseFragment extends ListaFragment {
    @Override
    protected int messaggioVuoto() {
        return R.string.vuoto_spese;
    }

    @Override
    protected void osserva() {
        app().db().spese().observeAll().observe(getViewLifecycleOwner(), spese -> {
            List<RigaAdapter.Riga> righe = new ArrayList<>();
            for (Spesa s : spese) {
                String sottotitolo = Date.mostra(s.data);
                if (s.categoria != null && !s.categoria.isEmpty()) {
                    sottotitolo += " · " + s.categoria;
                }
                String etichetta = !s.synced ? getString(R.string.in_attesa_sync)
                        : s.haGiustificativo() ? getString(R.string.con_giustificativo) : "";
                righe.add(new RigaAdapter.Riga(s.id, s.descrizione, sottotitolo, Money.format(s.importo),
                        etichetta, 0));
            }
            mostra(righe);
        });
    }

    @Override
    protected void alTocco(RigaAdapter.Riga riga) {
        startActivity(new Intent(requireContext(), SpesaEditActivity.class)
                .putExtra(SpesaEditActivity.EXTRA_ID, riga.id));
    }

    @Override
    protected void alAggiungi() {
        startActivity(new Intent(requireContext(), SpesaEditActivity.class));
    }
}
