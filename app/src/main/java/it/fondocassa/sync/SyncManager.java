package it.fondocassa.sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.fondocassa.data.Alunno;
import it.fondocassa.data.AppDatabase;
import it.fondocassa.data.Entrata;
import it.fondocassa.data.Quota;
import it.fondocassa.data.Repository;
import it.fondocassa.data.Settings;
import it.fondocassa.data.Spesa;
import it.fondocassa.sync.ApiModels.AlunnoDto;
import it.fondocassa.sync.ApiModels.BackupResponse;
import it.fondocassa.sync.ApiModels.EntrataDto;
import it.fondocassa.sync.ApiModels.ExportResponse;
import it.fondocassa.sync.ApiModels.QuotaDto;
import it.fondocassa.sync.ApiModels.SpesaDto;
import it.fondocassa.sync.ApiModels.SyncRequest;
import it.fondocassa.sync.ApiModels.SyncResponse;

/**
 * Esportazione (App → Sheets) dei record con {@code is_synced = 0} e ripristino completo
 * (Sheets → App). Le operazioni sono serializzate: sync manuale e in background non si accavallano.
 */
public class SyncManager {
    private static final Object LOCK = new Object();

    /** Esito di una sincronizzazione. */
    public static class Esito {
        public final int inviati;
        public final int giustificativiCaricati;
        /** Allegati che lo script ha rifiutato: le relative spese restano in attesa. */
        public final int giustificativiFalliti;
        /** Messaggio dello script per il primo allegato rifiutato, o null. */
        public final String erroreGiustificativi;

        Esito(int inviati, Upload upload) {
            this.inviati = inviati;
            this.giustificativiCaricati = upload.caricati;
            this.giustificativiFalliti = upload.falliti.size();
            this.erroreGiustificativi = upload.errore;
        }
    }

    /** Risultato del caricamento degli allegati. */
    private static class Upload {
        int caricati;
        final Set<String> falliti = new HashSet<>();
        String errore;
    }

    private final AppDatabase db;
    private final Settings settings;
    private final ApiClient api;

    public SyncManager(AppDatabase db, Settings settings, ApiClient api) {
        this.db = db;
        this.settings = settings;
        this.api = api;
    }

    public static SyncManager crea(AppDatabase db, Settings settings) {
        return new SyncManager(db, settings, new ApiClient(settings.endpoint(), settings.token()));
    }

    /** Invia i record non sincronizzati; in caso di errore restano in attesa per il tentativo successivo. */
    public Esito sincronizza() throws IOException {
        synchronized (LOCK) {
            Upload upload = caricaGiustificativi();

            List<Alunno> alunni = db.alunni().pendenti();
            List<Quota> quote = db.quote().pendenti();
            List<Entrata> entrate = db.entrate().pendenti();
            List<Spesa> spese = db.spese().pendenti();
            // Una spesa il cui allegato non è stato caricato resta in attesa: verrà riproposta
            // con il suo file alla prossima sincronizzazione, senza bloccare tutto il resto.
            spese.removeIf(s -> upload.falliti.contains(s.id));
            if (alunni.isEmpty() && quote.isEmpty() && entrate.isEmpty() && spese.isEmpty()) {
                return new Esito(0, upload);
            }

            SyncRequest richiesta = new SyncRequest();
            Map<String, Long> versioniAlunni = new HashMap<>();
            Map<String, Long> versioniQuote = new HashMap<>();
            Map<String, Long> versioniEntrate = new HashMap<>();
            Map<String, Long> versioniSpese = new HashMap<>();
            for (Alunno a : alunni) {
                richiesta.alunni.add(AlunnoDto.da(a));
                versioniAlunni.put(a.id, a.aggiornato);
            }
            for (Quota q : quote) {
                richiesta.quote.add(QuotaDto.da(q));
                versioniQuote.put(q.id, q.aggiornato);
            }
            for (Entrata e : entrate) {
                richiesta.entrate.add(EntrataDto.da(e));
                versioniEntrate.put(e.id, e.aggiornato);
            }
            for (Spesa s : spese) {
                richiesta.spese.add(SpesaDto.da(s));
                versioniSpese.put(s.id, s.aggiornato);
            }

            SyncResponse risposta = api.sync(richiesta);
            String ts = risposta.timestampSync == null ? "" : risposta.timestampSync;

            int[] confermati = {0};
            db.runInTransaction(() -> {
                for (String id : nonNull(risposta.alunni)) {
                    Long versione = versioniAlunni.get(id);
                    if (versione != null) {
                        db.alunni().confermaSync(id, versione, ts);
                        confermati[0]++;
                    }
                }
                for (String id : nonNull(risposta.quote)) {
                    Long versione = versioniQuote.get(id);
                    if (versione != null) {
                        db.quote().confermaSync(id, versione, ts);
                        confermati[0]++;
                    }
                }
                for (String id : nonNull(risposta.entrate)) {
                    Long versione = versioniEntrate.get(id);
                    if (versione != null) {
                        db.entrate().confermaSync(id, versione, ts);
                        confermati[0]++;
                    }
                }
                for (String id : nonNull(risposta.spese)) {
                    Long versione = versioniSpese.get(id);
                    if (versione != null) {
                        db.spese().confermaSync(id, versione, ts);
                        confermati[0]++;
                    }
                }
                db.alunni().rimuoviEliminatiSincronizzati();
                db.quote().rimuoviEliminatiSincronizzati();
                db.entrate().rimuoviEliminatiSincronizzati();
                db.spese().rimuoviEliminatiSincronizzati();
            });
            settings.setUltimaSync(ts);
            return new Esito(confermati[0], upload);
        }
    }

    /**
     * Carica su Drive i giustificativi locali e ne salva l'URL nel record, che resta da sincronizzare.
     * Un rifiuto dello script (permessi di Drive, file troppo grande…) riguarda il singolo allegato
     * e viene annotato; un errore di rete invece interrompe la sincronizzazione.
     */
    private Upload caricaGiustificativi() throws IOException {
        Upload upload = new Upload();
        for (Spesa s : db.spese().pendenti()) {
            if (s.eliminato || !s.uploadInAttesa()) {
                continue;
            }
            File file = new File(s.giustificativoLocale);
            if (!file.isFile()) {
                continue; // file perso: la spesa viene comunque sincronizzata senza allegato
            }
            String mime = s.giustificativoMime == null ? "application/octet-stream" : s.giustificativoMime;
            String url;
            try {
                url = api.uploadGiustificativo(s.id, file.getName(), mime, Files.readAllBytes(file.toPath()));
            } catch (ApiException e) {
                upload.falliti.add(s.id);
                if (upload.errore == null) {
                    upload.errore = e.getMessage();
                }
                continue;
            }
            if (db.spese().impostaUrlGiustificativo(s.id, s.giustificativoLocale, url) > 0) {
                Repository.cancellaFile(s.giustificativoLocale);
            }
            upload.caricati++;
        }
        return upload;
    }

    /**
     * Ricostruisce il database locale dal foglio Google (GET ?action=export_all).
     * I dati locali vengono sostituiti solo dopo aver ricevuto una risposta valida.
     *
     * @return numero di record importati
     */
    public int ripristina() throws IOException {
        synchronized (LOCK) {
            ExportResponse export = api.exportAll();
            List<Alunno> alunni = new ArrayList<>();
            List<Quota> quote = new ArrayList<>();
            List<Entrata> entrate = new ArrayList<>();
            List<Spesa> spese = new ArrayList<>();
            for (AlunnoDto d : nonNull(export.alunni)) {
                if (d.id != null && !d.id.isEmpty()) {
                    alunni.add(d.inEntita());
                }
            }
            for (QuotaDto d : nonNull(export.quote)) {
                if (d.id != null && !d.id.isEmpty()) {
                    quote.add(d.inEntita());
                }
            }
            for (EntrataDto d : nonNull(export.entrate)) {
                if (d.id != null && !d.id.isEmpty()) {
                    entrate.add(d.inEntita());
                }
            }
            for (SpesaDto d : nonNull(export.spese)) {
                if (d.id != null && !d.id.isEmpty()) {
                    spese.add(d.inEntita());
                }
            }
            db.runInTransaction(() -> {
                db.alunni().deleteAll();
                db.quote().deleteAll();
                db.entrate().deleteAll();
                db.spese().deleteAll();
                db.alunni().insertAll(alunni);
                db.quote().insertAll(quote);
                db.entrate().insertAll(entrate);
                db.spese().insertAll(spese);
            });
            return alunni.size() + quote.size() + entrate.size() + spese.size();
        }
    }

    /**
     * Esporta su Google Drive uno ZIP con il database SQLite e tutti i giustificativi.
     * Prima sincronizza, così anche gli allegati ancora sul telefono finiscono su Drive e nello ZIP.
     *
     * @param fileDatabase il file SQLite dell'app
     */
    public BackupResponse esportaBackup(File fileDatabase) throws IOException {
        synchronized (LOCK) {
            sincronizza();
            // Riversa nel file principale le scritture ancora nel journal (WAL) prima di leggerlo.
            try (android.database.Cursor c = db.getOpenHelper().getWritableDatabase()
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")) {
                c.moveToFirst();
            }
            if (!fileDatabase.isFile()) {
                throw new IOException("Database non trovato");
            }
            return api.backup(Files.readAllBytes(fileDatabase.toPath()));
        }
    }

    private static <T> List<T> nonNull(List<T> lista) {
        return lista == null ? new ArrayList<>() : lista;
    }
}
