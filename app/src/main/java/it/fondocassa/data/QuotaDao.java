package it.fondocassa.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface QuotaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(Quota quota);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Quota> quote);

    @Query("SELECT * FROM quote WHERE id_quota = :id")
    Quota get(String id);

    @Query("SELECT * FROM quote WHERE eliminato = 0 ORDER BY data DESC, aggiornato DESC")
    LiveData<List<Quota>> observeAll();

    /** Dovuto per alunno: somma di tutte le quote richieste. */
    @Query("SELECT COALESCE(SUM(importo), 0) FROM quote WHERE eliminato = 0")
    LiveData<Long> observeTotale();

    @Query("SELECT COALESCE(SUM(importo), 0) FROM quote WHERE eliminato = 0")
    long totale();

    @Query("SELECT * FROM quote WHERE is_synced = 0")
    List<Quota> pendenti();

    @Query("SELECT COUNT(*) FROM quote WHERE is_synced = 0")
    LiveData<Integer> observePendenti();

    @Query("UPDATE quote SET is_synced = 1, timestamp_sync = :timestamp "
            + "WHERE id_quota = :id AND aggiornato = :aggiornato")
    void confermaSync(String id, long aggiornato, String timestamp);

    /** Rimette in attesa ogni record: la prossima sincronizzazione li riscrive tutti sul foglio. */
    @Query("UPDATE quote SET is_synced = 0")
    void marcaTuttiDaInviare();

    @Query("DELETE FROM quote WHERE eliminato = 1 AND is_synced = 1")
    void rimuoviEliminatiSincronizzati();

    @Query("DELETE FROM quote WHERE id_quota = :id")
    void delete(String id);

    /** Marca eliminato ogni record: la sincronizzazione toglierà le righe anche dal foglio. */
    @Query("UPDATE quote SET eliminato = 1, is_synced = 0, aggiornato = :adesso")
    void eliminaTutti(long adesso);

    @Query("DELETE FROM quote")
    void deleteAll();
}
