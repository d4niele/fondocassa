package it.fondocassa.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {Alunno.class, Quota.class, Entrata.class, Spesa.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public static final String NOME_FILE = "fondocassa.db";

    public abstract AlunnoDao alunni();

    public abstract QuotaDao quote();

    public abstract EntrataDao entrate();

    public abstract SpesaDao spese();

    public static AppDatabase apri(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, NOME_FILE)
                .build();
    }
}
