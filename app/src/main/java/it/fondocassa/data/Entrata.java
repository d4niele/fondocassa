package it.fondocassa.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Quota versata da un alunno (1:1 con il foglio Sync_Entrate). */
@Entity(tableName = "entrate", indices = {@Index("alunno_id")})
public class Entrata {
    public static final String[] METODI = {"Contante", "Satispay", "Bancomat Pay", "Hype", "Postepay", "PayPal", "Bonifico"};

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id_transazione")
    public String id = "";

    @ColumnInfo(name = "alunno_id")
    public String alunnoId = "";

    /** Data in formato ISO yyyy-MM-dd. */
    public String data = "";

    /** Importo in centesimi di euro. */
    public long importo;

    public String metodo = METODI[0];

    public String note = "";

    @ColumnInfo(name = "timestamp_sync")
    public String timestampSync;

    @ColumnInfo(name = "is_synced")
    public boolean synced;

    public boolean eliminato;

    public long aggiornato;
}
