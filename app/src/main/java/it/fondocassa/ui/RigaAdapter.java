package it.fondocassa.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import it.fondocassa.R;

/** Adapter unico per le liste di alunni, entrate e spese. */
public class RigaAdapter extends RecyclerView.Adapter<RigaAdapter.Holder> {

    public static class Riga {
        public final String id;
        public final String titolo;
        public final String sottotitolo;
        public final String valore;
        public final String etichetta;
        /** Colore dell'etichetta, 0 per quello predefinito. */
        public final int coloreEtichetta;

        public Riga(String id, String titolo, String sottotitolo, String valore, String etichetta,
                int coloreEtichetta) {
            this.id = id;
            this.titolo = titolo;
            this.sottotitolo = sottotitolo;
            this.valore = valore;
            this.etichetta = etichetta;
            this.coloreEtichetta = coloreEtichetta;
        }
    }

    private final List<Riga> righe = new ArrayList<>();
    private final Consumer<Riga> alTocco;
    /** Pressione prolungata su una riga; null se la lista non la gestisce. */
    private final Consumer<Riga> alToccoLungo;

    public RigaAdapter(Consumer<Riga> alTocco) {
        this(alTocco, null);
    }

    public RigaAdapter(Consumer<Riga> alTocco, Consumer<Riga> alToccoLungo) {
        this.alTocco = alTocco;
        this.alToccoLungo = alToccoLungo;
    }

    public void imposta(List<Riga> nuove) {
        righe.clear();
        righe.addAll(nuove);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_riga, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Riga r = righe.get(position);
        h.titolo.setText(r.titolo);
        h.sottotitolo.setText(r.sottotitolo);
        h.sottotitolo.setVisibility(r.sottotitolo.isEmpty() ? View.GONE : View.VISIBLE);
        h.valore.setText(r.valore);
        h.etichetta.setText(r.etichetta);
        h.etichetta.setVisibility(r.etichetta.isEmpty() ? View.GONE : View.VISIBLE);
        h.etichetta.setTextColor(r.coloreEtichetta != 0 ? r.coloreEtichetta : h.coloreBase);
        h.itemView.setOnClickListener(v -> alTocco.accept(r));
        if (alToccoLungo != null) {
            h.itemView.setOnLongClickListener(v -> {
                alToccoLungo.accept(r);
                return true;
            });
        }
    }

    @Override
    public int getItemCount() {
        return righe.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView titolo;
        final TextView sottotitolo;
        final TextView valore;
        final TextView etichetta;
        final int coloreBase;

        Holder(View v) {
            super(v);
            titolo = v.findViewById(R.id.titolo);
            sottotitolo = v.findViewById(R.id.sottotitolo);
            valore = v.findViewById(R.id.valore);
            etichetta = v.findViewById(R.id.etichetta);
            coloreBase = sottotitolo.getCurrentTextColor();
        }
    }
}
