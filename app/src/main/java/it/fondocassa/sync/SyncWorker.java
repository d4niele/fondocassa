package it.fondocassa.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import it.fondocassa.FondocassaApp;

/** Sincronizzazione automatica in background: parte appena c'è rete dopo ogni modifica. */
public class SyncWorker extends Worker {
    private static final String NOME = "sync";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Accoda una sincronizzazione (le richieste ravvicinate vengono accorpate). */
    public static void accoda(Context context) {
        OneTimeWorkRequest richiesta = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(NOME, ExistingWorkPolicy.REPLACE, richiesta);
    }

    @NonNull
    @Override
    public Result doWork() {
        FondocassaApp app = (FondocassaApp) getApplicationContext();
        if (!app.settings().connessioneConfigurata()) {
            return Result.success();
        }
        try {
            SyncManager.crea(app.db(), app.settings()).sincronizza();
            return Result.success();
        } catch (ApiException e) {
            // Errore applicativo (es. token errato): riprovare non serve, l'utente lo vedrà con "Sincronizza".
            return Result.failure();
        } catch (IOException e) {
            return getRunAttemptCount() < 5 ? Result.retry() : Result.failure();
        }
    }
}
