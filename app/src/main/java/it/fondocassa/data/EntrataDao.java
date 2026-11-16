package it.fondocassa.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface EntrataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(Entrata entrata);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Entrata> entrate);

    @Query("SELECT * FROM entrate WHERE id_transazione = :id")
    Entrata get(String id);

    @Query("SELECT e.*, a.nome AS nomeAlunno FROM entrate e "
            + "LEFT JOIN alunni a ON a.alunno_id = e.alunno_id "
            + "WHERE e.eliminato = 0 ORDER BY e.data DESC, e.aggiornato DESC")
    LiveData<List<EntrataConAlunno>> observeAll();

    @Query("SELECT COALESCE(SUM(importo), 0) FROM entrate WHERE eliminato = 0")
    LiveData<Long> observeTotale();

    @Query("SELECT COALESCE(SUM(importo), 0) FROM entrate WHERE eliminato = 0")
    long totale();

    @Query("SELECT COUNT(*) FROM entrate WHERE alunno_id = :alunnoId AND eliminato = 0")
    int contaPerAlunno(String alunnoId);

    @Query("SELECT * FROM entrate WHERE is_synced = 0")
    List<Entrata> pendenti();

    @Query("SELECT COUNT(*) FROM entrate WHERE is_synced = 0")
    LiveData<Integer> observePendenti();

    @Query("UPDATE entrate SET is_synced = 1, timestamp_sync = :timestamp "
            + "WHERE id_transazione = :id AND aggiornato = :aggiornato")
    void confermaSync(String id, long aggiornato, String timestamp);

    /** Rimette in attesa ogni record: la prossima sincronizzazione li riscrive tutti sul foglio. */
    @Query("UPDATE entrate SET is_synced = 0")
    void marcaTuttiDaInviare();

    @Query("DELETE FROM entrate WHERE eliminato = 1 AND is_synced = 1")
    void rimuoviEliminatiSincronizzati();

    @Query("DELETE FROM entrate WHERE id_transazione = :id")
    void delete(String id);

    @Query("DELETE FROM entrate")
    void deleteAll();
}
