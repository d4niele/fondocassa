package it.fondocassa.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Quota uguale per tutti gli alunni (es. "Quota di avvio" da 5 €).
 * Il dovuto di ogni alunno è la somma di tutte le quote (1:1 con il foglio Sync_Quote).
 */
@Entity(tableName = "quote")
public class Quota {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id_quota")
    public String id = "";

    public String descrizione = "";

    /** Importo richiesto a ciascun alunno, in centesimi. */
    public long importo;

    /** Data in formato ISO yyyy-MM-dd. */
    public String data = "";

    @ColumnInfo(name = "timestamp_sync")
    public String timestampSync;

    @ColumnInfo(name = "is_synced")
    public boolean synced;

    public boolean eliminato;

    public long aggiornato;
}
