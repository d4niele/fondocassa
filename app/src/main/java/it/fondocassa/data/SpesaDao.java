package it.fondocassa.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SpesaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(Spesa spesa);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Spesa> spese);

    @Query("SELECT * FROM spese WHERE id_spesa = :id")
    Spesa get(String id);

    @Query("SELECT * FROM spese WHERE eliminato = 0 ORDER BY data DESC, aggiornato DESC")
    LiveData<List<Spesa>> observeAll();

    @Query("SELECT COALESCE(SUM(importo), 0) FROM spese WHERE eliminato = 0")
    LiveData<Long> observeTotale();

    @Query("SELECT COALESCE(SUM(importo), 0) FROM spese WHERE eliminato = 0")
    long totale();

    @Query("SELECT DISTINCT categoria FROM spese WHERE eliminato = 0 AND categoria <> '' ORDER BY categoria")
    List<String> categorie();

    @Query("SELECT * FROM spese WHERE is_synced = 0")
    List<Spesa> pendenti();

    @Query("SELECT COUNT(*) FROM spese WHERE is_synced = 0")
    LiveData<Integer> observePendenti();

    /** Salva l'URL restituito da Drive senza toccare gli altri campi (che l'utente potrebbe star modificando). */
    @Query("UPDATE spese SET url_giustificativo = :url, giustificativo_locale = NULL, "
            + "giustificativo_mime = NULL, is_synced = 0 "
            + "WHERE id_spesa = :id AND giustificativo_locale = :percorsoLocale")
    int impostaUrlGiustificativo(String id, String percorsoLocale, String url);

    @Query("UPDATE spese SET is_synced = 1, timestamp_sync = :timestamp "
            + "WHERE id_spesa = :id AND aggiornato = :aggiornato")
    void confermaSync(String id, long aggiornato, String timestamp);

    @Query("DELETE FROM spese WHERE eliminato = 1 AND is_synced = 1")
    void rimuoviEliminatiSincronizzati();

    @Query("DELETE FROM spese WHERE id_spesa = :id")
    void delete(String id);

    @Query("DELETE FROM spese")
    void deleteAll();
}
