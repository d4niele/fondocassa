package it.fondocassa.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AlunnoDao {
    String CON_SALDO = "SELECT a.*, COALESCE((SELECT SUM(e.importo) FROM entrate e "
            + "WHERE e.alunno_id = a.alunno_id AND e.eliminato = 0), 0) AS totaleVersato "
            + "FROM alunni a WHERE a.eliminato = 0 ORDER BY a.nome COLLATE NOCASE";

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(Alunno alunno);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Alunno> alunni);

    @Query("SELECT * FROM alunni WHERE alunno_id = :id")
    Alunno get(String id);

    @Query(CON_SALDO)
    LiveData<List<AlunnoConSaldo>> observeConSaldo();

    @Query(CON_SALDO)
    List<AlunnoConSaldo> listConSaldo();

    @Query("SELECT * FROM alunni WHERE eliminato = 0 ORDER BY nome COLLATE NOCASE")
    List<Alunno> list();

    @Query("SELECT * FROM alunni WHERE is_synced = 0")
    List<Alunno> pendenti();

    @Query("SELECT COUNT(*) FROM alunni WHERE is_synced = 0")
    LiveData<Integer> observePendenti();

    @Query("UPDATE alunni SET is_synced = 1, timestamp_sync = :timestamp "
            + "WHERE alunno_id = :id AND aggiornato = :aggiornato")
    void confermaSync(String id, long aggiornato, String timestamp);

    @Query("DELETE FROM alunni WHERE eliminato = 1 AND is_synced = 1")
    void rimuoviEliminatiSincronizzati();

    @Query("DELETE FROM alunni WHERE alunno_id = :id")
    void delete(String id);

    @Query("DELETE FROM alunni")
    void deleteAll();
}
