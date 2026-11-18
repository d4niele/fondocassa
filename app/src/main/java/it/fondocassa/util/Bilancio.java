package it.fondocassa.util;

import java.util.List;

import it.fondocassa.data.AlunnoConSaldo;

/**
 * Situazione del fondo cassa.
 *
 * <ul>
 *   <li><b>fondoIniziale</b>: l'avanzo degli anni passati, con cui la cassa parte;</li>
 *   <li><b>previsto</b>: quanto la classe deve versare in tutto (quote × alunni attivi);</li>
 *   <li><b>versato</b>: quanto è stato effettivamente incassato;</li>
 *   <li><b>daVersare</b>: quanto manca, sommando il residuo di ogni alunno attivo;</li>
 *   <li><b>cassaReale</b>: i soldi che ci sono davvero (fondo iniziale + versato − spese);</li>
 *   <li><b>cassaVirtuale</b>: i soldi che ci sarebbero se tutti avessero pagato (reale + da versare).</li>
 * </ul>
 * Tutti gli importi sono in centesimi.
 */
public final class Bilancio {
    public final long fondoIniziale;
    public final long previsto;
    public final long versato;
    public final long daVersare;
    public final long spese;
    public final long cassaReale;
    public final long cassaVirtuale;
    public final int alunniAttivi;
    public final int alunniInRegola;

    private Bilancio(long fondoIniziale, long previsto, long versato, long daVersare, long spese, int attivi,
            int inRegola) {
        this.fondoIniziale = fondoIniziale;
        this.previsto = previsto;
        this.versato = versato;
        this.daVersare = daVersare;
        this.spese = spese;
        this.cassaReale = fondoIniziale + versato - spese;
        this.cassaVirtuale = cassaReale + daVersare;
        this.alunniAttivi = attivi;
        this.alunniInRegola = inRegola;
    }

    /** Come {@link #calcola(List, long, long, long, long)} senza fondo iniziale. */
    public static Bilancio calcola(List<AlunnoConSaldo> alunni, long dovutoPerAlunno, long totaleEntrate,
            long totaleSpese) {
        return calcola(alunni, dovutoPerAlunno, totaleEntrate, totaleSpese, 0);
    }

    /**
     * @param alunni         alunni con il totale versato da ciascuno
     * @param dovutoPerAlunno somma delle quote richieste a ogni alunno
     * @param totaleEntrate  tutte le entrate registrate (anche di alunni non più attivi)
     * @param totaleSpese    tutte le uscite
     * @param fondoIniziale  avanzo degli anni passati
     */
    public static Bilancio calcola(List<AlunnoConSaldo> alunni, long dovutoPerAlunno, long totaleEntrate,
            long totaleSpese, long fondoIniziale) {
        int attivi = 0;
        int inRegola = 0;
        long daVersare = 0;
        for (AlunnoConSaldo a : alunni) {
            if (!a.alunno.attivo) {
                continue;
            }
            attivi++;
            long residuo = StatoPagamento.residuo(a.totaleVersato, dovutoPerAlunno);
            daVersare += residuo;
            if (residuo == 0) {
                inRegola++;
            }
        }
        return new Bilancio(fondoIniziale, dovutoPerAlunno * attivi, totaleEntrate, daVersare, totaleSpese, attivi, inRegola);
    }
}
