package it.fondocassa.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Soldi in cassa che non vengono dalle quote di quest'anno: il fondo iniziale, cioè l'avanzo
 * degli anni passati (1:1 con il foglio Sync_Fondi). Oggi l'app ne usa uno solo, con id
 * {@link #ID_INIZIALE}; la tabella resta aperta ad altri riporti.
 */
@Entity(tableName = "fondi")
public class Fondo {
    public static final String ID_INIZIALE = "fondo_iniziale";

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id_fondo")
    public String id = "";

    public String descrizione = "";

    /** In centesimi. */
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
