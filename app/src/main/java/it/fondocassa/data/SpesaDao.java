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

    /** In ordine cronologico, per il PDF. */
    @Query("SELECT * FROM spese WHERE eliminato = 0 ORDER BY data, aggiornato")
    List<Spesa> list();

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

    /** Rimette in attesa ogni record: la prossima sincronizzazione li riscrive tutti sul foglio. */
    @Query("UPDATE spese SET is_synced = 0")
    void marcaTuttiDaInviare();

    @Query("DELETE FROM spese WHERE eliminato = 1 AND is_synced = 1")
    void rimuoviEliminatiSincronizzati();

    @Query("DELETE FROM spese WHERE id_spesa = :id")
    void delete(String id);

    /** Marca eliminata ogni spesa: la sincronizzazione toglierà le righe anche dal foglio. */
    @Query("UPDATE spese SET eliminato = 1, is_synced = 0, aggiornato = :adesso, "
            + "giustificativo_locale = NULL, giustificativo_mime = NULL")
    void eliminaTutti(long adesso);

    @Query("SELECT giustificativo_locale FROM spese WHERE giustificativo_locale IS NOT NULL")
    List<String> allegatiLocali();

    @Query("DELETE FROM spese")
    void deleteAll();
}
