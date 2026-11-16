package it.fondocassa.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Anagrafica alunno. */
@Entity(tableName = "alunni")
public class Alunno {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "alunno_id")
    public String id = "";

    public String nome = "";

    /** Appunti liberi, ad esempio i nomi dei genitori. Restano nel foglio privato. */
    public String note = "";

    /** Un alunno non attivo (es. trasferito) resta nello storico ma non conta come "in sospeso". */
    public boolean attivo = true;

    /** Cancellazione logica in attesa di essere propagata al foglio. */
    public boolean eliminato;

    @ColumnInfo(name = "is_synced")
    public boolean synced;

    @ColumnInfo(name = "timestamp_sync")
    public String timestampSync;

    /** Istante (ms) dell'ultima modifica locale: evita di marcare come sincronizzata una modifica più recente dell'invio. */
    public long aggiornato;
}
