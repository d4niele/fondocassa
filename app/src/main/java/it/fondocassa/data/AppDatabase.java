package it.fondocassa.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Alunno.class, Quota.class, Entrata.class, Spesa.class, Fondo.class}, version = 2,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public static final String NOME_FILE = "fondocassa.db";

    /** 1.1 → 1.2: tabella del fondo iniziale. I dati esistenti restano intatti. */
    public static final Migration MIGRAZIONE_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `fondi` (`id_fondo` TEXT NOT NULL, `descrizione` TEXT, "
                    + "`importo` INTEGER NOT NULL, `data` TEXT, `timestamp_sync` TEXT, "
                    + "`is_synced` INTEGER NOT NULL, `eliminato` INTEGER NOT NULL, "
                    + "`aggiornato` INTEGER NOT NULL, PRIMARY KEY(`id_fondo`))");
        }
    };

    public abstract AlunnoDao alunni();

    public abstract QuotaDao quote();

    public abstract EntrataDao entrate();

    public abstract SpesaDao spese();

    public abstract FondoDao fondi();

    public static AppDatabase apri(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, NOME_FILE)
                .addMigrations(MIGRAZIONE_1_2)
                .build();
    }
}
