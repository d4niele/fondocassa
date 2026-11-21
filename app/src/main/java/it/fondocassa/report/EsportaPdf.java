package it.fondocassa.report;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Locale;

import it.fondocassa.data.AppDatabase;
import it.fondocassa.data.Repository;
import it.fondocassa.data.Settings;
import it.fondocassa.data.Spesa;
import it.fondocassa.report.PdfRapporto.Allegato;
import it.fondocassa.sync.ApiClient;
import it.fondocassa.sync.ApiException;

/**
 * Crea il rendiconto in PDF. I giustificativi ancora da sincronizzare sono sul telefono; quelli già
 * caricati vengono scaricati da Drive attraverso lo script, in una cartella temporanea.
 */
public final class EsportaPdf {
    private EsportaPdf() {
    }

    /**
     * @param cartellaTemporanea dove appoggiare i giustificativi scaricati (svuotata alla fine)
     * @param api                null se il collegamento al foglio non è configurato
     */
    public static PdfRapporto.Esito crea(Rapporto rapporto, ApiClient api, File cartellaTemporanea,
            OutputStream out) throws IOException {
        Fonte fonte = new Fonte(api, cartellaTemporanea);
        try {
            return PdfRapporto.scrivi(rapporto, fonte, out);
        } finally {
            fonte.pulisci();
        }
    }

    /** Il rapporto con i dati di adesso. */
    public static Rapporto rapporto(AppDatabase db, Settings settings, String versioneApp) {
        return Rapporto.leggi(db, settings.classe(), versioneApp, LocalDateTime.now());
    }

    static final class Fonte implements PdfRapporto.FonteAllegati {
        private final ApiClient api;
        private final File cartella;
        /** Dopo un errore di rete non si riprova a ogni allegato: si eviterebbero solo lunghe attese. */
        private String senzaRete;

        Fonte(ApiClient api, File cartella) {
            this.api = api;
            this.cartella = cartella;
        }

        @Override
        public Allegato trova(Spesa s) {
            if (s.giustificativoLocale != null && new File(s.giustificativoLocale).isFile()) {
                return Allegato.file(new File(s.giustificativoLocale), s.giustificativoMime);
            }
            if (s.urlGiustificativo == null || s.urlGiustificativo.isEmpty()) {
                return Allegato.mancante("il file non è più sul telefono");
            }
            if (api == null) {
                return Allegato.mancante("per scaricarlo da Drive serve il collegamento al foglio nelle Impostazioni");
            }
            if (senzaRete != null) {
                return Allegato.mancante(senzaRete);
            }
            try {
                ApiClient.Scaricato file = api.scaricaGiustificativo(s.id);
                //noinspection ResultOfMethodCallIgnored
                cartella.mkdirs();
                boolean pdf = "application/pdf".equals(file.mime) || file.nome.toLowerCase(Locale.ROOT).endsWith(".pdf");
                File destinazione = new File(cartella, s.id + (pdf ? ".pdf" : ".img"));
                Files.write(destinazione.toPath(), file.contenuto);
                return Allegato.file(destinazione, pdf ? "application/pdf" : file.mime);
            } catch (ApiException e) {
                return Allegato.mancante("Drive non l'ha restituito (" + e.getMessage() + ")");
            } catch (IOException e) {
                senzaRete = "non è stato possibile scaricarlo da Drive: controlla la connessione";
                return Allegato.mancante(senzaRete);
            }
        }

        void pulisci() {
            File[] file = cartella.listFiles();
            if (file != null) {
                for (File f : file) {
                    Repository.cancellaFile(f.getPath());
                }
            }
        }
    }
}
