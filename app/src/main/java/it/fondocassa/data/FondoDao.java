package it.fondocassa.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FondoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(Fondo fondo);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Fondo> fondi);

    @Query("SELECT * FROM fondi WHERE id_fondo = :id")
    Fondo get(String id);

    @Query("SELECT * FROM fondi WHERE id_fondo = :id AND eliminato = 0")
    LiveData<Fondo> observe(String id);

    @Query("SELECT COALESCE(SUM(importo), 0) FROM fondi WHERE eliminato = 0")
    LiveData<Long> observeTotale();

    @Query("SELECT COALESCE(SUM(importo), 0) FROM fondi WHERE eliminato = 0")
    long totale();

    @Query("SELECT * FROM fondi WHERE is_synced = 0")
    List<Fondo> pendenti();

    @Query("SELECT COUNT(*) FROM fondi WHERE is_synced = 0")
    LiveData<Integer> observePendenti();

    @Query("UPDATE fondi SET is_synced = 1, timestamp_sync = :timestamp "
            + "WHERE id_fondo = :id AND aggiornato = :aggiornato")
    void confermaSync(String id, long aggiornato, String timestamp);

    @Query("UPDATE fondi SET is_synced = 0")
    void marcaTuttiDaInviare();

    @Query("DELETE FROM fondi WHERE eliminato = 1 AND is_synced = 1")
    void rimuoviEliminatiSincronizzati();

    @Query("DELETE FROM fondi")
    void deleteAll();
}
