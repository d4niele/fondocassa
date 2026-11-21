package it.fondocassa.report;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import it.fondocassa.data.AlunnoConSaldo;
import it.fondocassa.data.AppDatabase;
import it.fondocassa.data.EntrataConAlunno;
import it.fondocassa.data.Fondo;
import it.fondocassa.data.Quota;
import it.fondocassa.data.Spesa;
import it.fondocassa.util.Bilancio;

/** Tutto ciò che finisce nel PDF, letto dal database in un colpo solo. */
public final class Rapporto {
    /** Il repository del progetto, citato in fondo a ogni pagina. */
    public static final String REPOSITORY = "github.com/d4niele/fondocassa";

    public final String classe;
    /** Versione dell'app che ha creato il PDF (es. «1.3.0»). */
    public final String versioneApp;
    /** Data e ora di creazione, già nel formato da mostrare (gg/mm/aaaa hh:mm). */
    public final String creatoIl;
    /** Data di creazione in formato ISO, per il nome del file. */
    public final String dataIso;
    public final Bilancio bilancio;
    /** Null se non c'è un fondo iniziale. */
    public final Fondo fondo;
    public final long dovutoPerAlunno;
    public final List<Quota> quote;
    public final List<AlunnoConSaldo> alunni;
    public final List<EntrataConAlunno> entrate;
    public final List<Spesa> spese;
    /** Le spese con un giustificativo, nell'ordine in cui compaiono: l'allegato n. 1 è la prima. */
    public final List<Spesa> conGiustificativo;

    Rapporto(String classe, String versioneApp, LocalDateTime adesso, Fondo fondo, List<Quota> quote,
            List<AlunnoConSaldo> alunni, List<EntrataConAlunno> entrate, List<Spesa> spese) {
        this.classe = classe == null ? "" : classe;
        this.versioneApp = versioneApp == null ? "" : versioneApp;
        this.creatoIl = adesso.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        this.dataIso = adesso.toLocalDate().toString();
        this.fondo = fondo != null && !fondo.eliminato && fondo.importo != 0 ? fondo : null;
        this.quote = Collections.unmodifiableList(quote);
        this.alunni = Collections.unmodifiableList(alunni);
        this.entrate = Collections.unmodifiableList(entrate);
        this.spese = Collections.unmodifiableList(spese);
        long dovuto = 0;
        for (Quota q : quote) {
            dovuto += q.importo;
        }
        this.dovutoPerAlunno = dovuto;
        long totaleEntrate = 0;
        for (EntrataConAlunno e : entrate) {
            totaleEntrate += e.entrata.importo;
        }
        long totaleSpese = 0;
        List<Spesa> allegati = new ArrayList<>();
        for (Spesa s : spese) {
            totaleSpese += s.importo;
            if (s.haGiustificativo()) {
                allegati.add(s);
            }
        }
        this.conGiustificativo = Collections.unmodifiableList(allegati);
        this.bilancio = Bilancio.calcola(alunni, dovuto, totaleEntrate, totaleSpese,
                this.fondo == null ? 0 : this.fondo.importo);
    }

    /** Legge i dati attuali. Va chiamato fuori dal thread principale. */
    public static Rapporto leggi(AppDatabase db, String classe, String versioneApp, LocalDateTime adesso) {
        Rapporto[] r = new Rapporto[1];
        // Una sola transazione: i totali e gli elenchi descrivono lo stesso istante.
        db.runInTransaction(() -> r[0] = new Rapporto(classe, versioneApp, adesso,
                db.fondi().get(Fondo.ID_INIZIALE), db.quote().list(), db.alunni().listConSaldo(),
                db.entrate().listConAlunno(), db.spese().list()));
        return r[0];
    }

    /** Numero dell'allegato di una spesa (da 1), o 0 se non ne ha. */
    public int numeroAllegato(Spesa spesa) {
        return conGiustificativo.indexOf(spesa) + 1;
    }

    /** Titolo del documento: «Fondo cassa 1ª C» o, senza classe, «Fondo cassa». */
    public String titolo() {
        return classe.isEmpty() ? "Fondo cassa" : "Fondo cassa " + classe;
    }

    /** Piè di pagina: «Creato con Fondo cassa 1.3.0 · github.com/d4niele/fondocassa». */
    public String piede() {
        return "Creato con Fondo cassa" + (versioneApp.isEmpty() ? "" : " " + versioneApp) + " · " + REPOSITORY;
    }

    /** Nome del file, senza caratteri problematici: «Fondo cassa 1ª C - 2026-11-21.pdf». */
    public String nomeFile() {
        return titolo().replaceAll("[\\\\/:*?\"<>|]", "-") + " - " + dataIso + ".pdf";
    }
}
