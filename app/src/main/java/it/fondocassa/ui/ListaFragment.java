package it.fondocassa.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;

/** Base delle schede a lista: RecyclerView, messaggio "vuoto" e pulsante +. */
public abstract class ListaFragment extends Fragment {
    private RigaAdapter adapter;
    private View vuotoBox;

    protected abstract int messaggioVuoto();

    protected abstract void alTocco(RigaAdapter.Riga riga);

    protected abstract void alAggiungi();

    protected abstract void osserva();

    /** Testo del pulsante mostrato quando la lista è vuota (0 = nessun pulsante). */
    protected int azioneVuoto() {
        return 0;
    }

    protected void alAzioneVuoto() {
    }

    protected FondocassaApp app() {
        return (FondocassaApp) requireActivity().getApplication();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lista, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new RigaAdapter(this::alTocco);
        RecyclerView lista = view.findViewById(R.id.lista);
        lista.setLayoutManager(new LinearLayoutManager(requireContext()));
        lista.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));
        lista.setAdapter(adapter);
        vuotoBox = view.findViewById(R.id.vuoto_box);
        ((TextView) view.findViewById(R.id.vuoto)).setText(messaggioVuoto());
        if (azioneVuoto() != 0) {
            TextView azione = view.findViewById(R.id.azione_vuoto);
            azione.setText(azioneVuoto());
            azione.setVisibility(View.VISIBLE);
            azione.setOnClickListener(v -> alAzioneVuoto());
        }
        view.findViewById(R.id.aggiungi).setOnClickListener(v -> alAggiungi());
        osserva();
    }

    protected void mostra(List<RigaAdapter.Riga> righe) {
        adapter.imposta(righe);
        vuotoBox.setVisibility(righe.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
