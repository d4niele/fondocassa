package it.fondocassa.data;

import java.io.File;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Operazioni di scrittura sul database locale. Ogni modifica viene marcata {@code is_synced = 0}
 * e notifica {@code onModifica} (che in produzione accoda la sincronizzazione in background).
 * I metodi sono sincroni: vanno chiamati fuori dal thread principale.
 */
public class Repository {
    private final AppDatabase db;
    private final Runnable onModifica;
    private final LongSupplier orologio;

    public Repository(AppDatabase db, Runnable onModifica) {
        this(db, onModifica, System::currentTimeMillis);
    }

    public Repository(AppDatabase db, Runnable onModifica, LongSupplier orologio) {
        this.db = db;
        this.onModifica = onModifica;
        this.orologio = orologio;
    }

    public static String nuovoId() {
        return UUID.randomUUID().toString();
    }

    public AppDatabase db() {
        return db;
    }

    // --- Alunni ---

    public void salvaAlunno(Alunno alunno) {
        if (alunno.id.isEmpty()) {
            alunno.id = nuovoId();
        }
        alunno.synced = false;
        alunno.eliminato = false;
        alunno.aggiornato = orologio.getAsLong();
        db.alunni().upsert(alunno);
        onModifica.run();
    }

    /**
     * Elimina un alunno solo se non ha quote registrate (per non lasciare incassi orfani).
     *
     * @return false se l'alunno ha entrate e non è stato eliminato
     */
    public boolean eliminaAlunno(String id) {
        Alunno alunno = db.alunni().get(id);
        if (alunno == null) {
            return true;
        }
        if (db.entrate().contaPerAlunno(id) > 0) {
            return false;
        }
        if (maiSincronizzato(alunno.timestampSync)) {
            db.alunni().delete(id);
        } else {
            alunno.eliminato = true;
            alunno.synced = false;
            alunno.aggiornato = orologio.getAsLong();
            db.alunni().upsert(alunno);
            onModifica.run();
        }
        return true;
    }

    // --- Quote ---

    public void salvaQuota(Quota quota) {
        if (quota.id.isEmpty()) {
            quota.id = nuovoId();
        }
        quota.synced = false;
        quota.eliminato = false;
        quota.aggiornato = orologio.getAsLong();
        db.quote().upsert(quota);
        onModifica.run();
    }

    public void eliminaQuota(String id) {
        Quota quota = db.quote().get(id);
        if (quota == null) {
            return;
        }
        if (maiSincronizzato(quota.timestampSync)) {
            db.quote().delete(id);
        } else {
            quota.eliminato = true;
            quota.synced = false;
            quota.aggiornato = orologio.getAsLong();
            db.quote().upsert(quota);
            onModifica.run();
        }
    }

    // --- Entrate ---

    public void salvaEntrata(Entrata entrata) {
        if (entrata.id.isEmpty()) {
            entrata.id = nuovoId();
        }
        entrata.synced = false;
        entrata.eliminato = false;
        entrata.aggiornato = orologio.getAsLong();
        db.entrate().upsert(entrata);
        onModifica.run();
    }

    public void eliminaEntrata(String id) {
        Entrata entrata = db.entrate().get(id);
        if (entrata == null) {
            return;
        }
        if (maiSincronizzato(entrata.timestampSync)) {
            db.entrate().delete(id);
        } else {
            entrata.eliminato = true;
            entrata.synced = false;
            entrata.aggiornato = orologio.getAsLong();
            db.entrate().upsert(entrata);
            onModifica.run();
        }
    }

    // --- Spese ---

    public void salvaSpesa(Spesa spesa) {
        if (spesa.id.isEmpty()) {
            spesa.id = nuovoId();
        }
        spesa.synced = false;
        spesa.eliminato = false;
        spesa.aggiornato = orologio.getAsLong();
        db.spese().upsert(spesa);
        onModifica.run();
    }

    public void eliminaSpesa(String id) {
        Spesa spesa = db.spese().get(id);
        if (spesa == null) {
            return;
        }
        cancellaFile(spesa.giustificativoLocale);
        if (maiSincronizzato(spesa.timestampSync)) {
            db.spese().delete(id);
        } else {
            spesa.eliminato = true;
            spesa.synced = false;
            spesa.giustificativoLocale = null;
            spesa.giustificativoMime = null;
            spesa.aggiornato = orologio.getAsLong();
            db.spese().upsert(spesa);
            onModifica.run();
        }
    }

    public static void cancellaFile(String percorso) {
        if (percorso != null && !percorso.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(percorso).delete();
        }
    }

    private static boolean maiSincronizzato(String timestampSync) {
        return timestampSync == null || timestampSync.isEmpty();
    }
}
