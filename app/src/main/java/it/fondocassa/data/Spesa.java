package it.fondocassa.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Uscita dal fondo cassa (1:1 con il foglio Sync_Spese). */
@Entity(tableName = "spese")
public class Spesa {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id_spesa")
    public String id = "";

    public String descrizione = "";

    public String categoria = "";

    /** Data in formato ISO yyyy-MM-dd. */
    public String data = "";

    /** Importo in centesimi di euro. */
    public long importo;

    /** Link al file su Google Drive, valorizzato dopo l'upload. */
    @ColumnInfo(name = "url_giustificativo")
    public String urlGiustificativo = "";

    /** Percorso del file locale in attesa di upload (solo sul dispositivo). */
    @ColumnInfo(name = "giustificativo_locale")
    public String giustificativoLocale;

    @ColumnInfo(name = "giustificativo_mime")
    public String giustificativoMime;

    @ColumnInfo(name = "timestamp_sync")
    public String timestampSync;

    @ColumnInfo(name = "is_synced")
    public boolean synced;

    public boolean eliminato;

    public long aggiornato;

    public boolean haGiustificativo() {
        return (urlGiustificativo != null && !urlGiustificativo.isEmpty())
                || (giustificativoLocale != null && !giustificativoLocale.isEmpty());
    }

    public boolean uploadInAttesa() {
        return (urlGiustificativo == null || urlGiustificativo.isEmpty())
                && giustificativoLocale != null && !giustificativoLocale.isEmpty();
    }
}
