package it.fondocassa;

import androidx.room.Room;

import it.fondocassa.data.AppDatabase;

/** Application usata nei test: database in memoria e nessuna sincronizzazione in background. */
public class TestApp extends FondocassaApp {
    public int syncAccodate;

    @Override
    public void onCreate() {
        super.onCreate();
        setDb(dbInMemoria(this));
    }

    public static AppDatabase dbInMemoria(android.content.Context context) {
        return Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .setQueryExecutor(Runnable::run)
                .setTransactionExecutor(Runnable::run)
                .build();
    }

    @Override
    protected void accodaSync() {
        syncAccodate++;
    }
}
