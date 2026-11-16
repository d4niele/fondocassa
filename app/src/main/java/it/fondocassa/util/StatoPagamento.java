package it.fondocassa.util;

/** Stato della quota di un alunno, come mostrato in app e nel foglio Vista_Classe. */
public enum StatoPagamento {
    IN_REGOLA("In regola"),
    IN_SOSPESO("In sospeso");

    public final String etichetta;

    StatoPagamento(String etichetta) {
        this.etichetta = etichetta;
    }

    public static StatoPagamento calcola(long versatoCent, long quotaPrevistaCent) {
        return versatoCent >= quotaPrevistaCent ? IN_REGOLA : IN_SOSPESO;
    }

    /** Quanto manca per essere in regola (0 se già in regola). */
    public static long residuo(long versatoCent, long quotaPrevistaCent) {
        return Math.max(0, quotaPrevistaCent - versatoCent);
    }
}
