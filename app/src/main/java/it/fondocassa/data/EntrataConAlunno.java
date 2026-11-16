package it.fondocassa.data;

import androidx.room.Embedded;

/** Entrata con il nome dell'alunno, per le liste. */
public class EntrataConAlunno {
    @Embedded
    public Entrata entrata;

    public String nomeAlunno;
}
