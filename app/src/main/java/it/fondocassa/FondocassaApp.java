package it.fondocassa;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.fondocassa.data.AppDatabase;
import it.fondocassa.data.Repository;
import it.fondocassa.data.Settings;
import it.fondocassa.sync.SyncWorker;

public class FondocassaApp extends Application {
    /** Dopo questo tempo in background l'app richiede di nuovo il PIN. */
    private static final long TOLLERANZA_BLOCCO_MS = 2 * 60 * 1000;

    private AppDatabase db;
    private Settings settings;
    private Repository repository;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private boolean bloccata = true;
    private int activityAvviate;
    private long inBackgroundDa;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ControlloBlocco());
    }

    public synchronized AppDatabase db() {
        if (db == null) {
            db = AppDatabase.apri(this);
        }
        return db;
    }

    /** Solo per i test: sostituisce il database con uno in memoria. */
    public synchronized void setDb(AppDatabase database) {
        db = database;
        repository = null;
    }

    public synchronized Settings settings() {
        if (settings == null) {
            settings = new Settings(this);
        }
        return settings;
    }

    public synchronized Repository repository() {
        if (repository == null) {
            repository = new Repository(db(), this::accodaSync);
        }
        return repository;
    }

    /** Esecutore per le operazioni su database e rete. */
    public ExecutorService io() {
        return io;
    }

    protected void accodaSync() {
        if (settings().connessioneConfigurata()) {
            SyncWorker.accoda(this);
        }
    }

    public boolean bloccata() {
        return bloccata;
    }

    public void sblocca() {
        bloccata = false;
    }

    private class ControlloBlocco implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            if (activityAvviate == 0 && inBackgroundDa != 0
                    && SystemClock.elapsedRealtime() - inBackgroundDa > TOLLERANZA_BLOCCO_MS) {
                bloccata = true;
            }
            activityAvviate++;
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            activityAvviate = Math.max(0, activityAvviate - 1);
            if (activityAvviate == 0) {
                inBackgroundDa = SystemClock.elapsedRealtime();
            }
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
