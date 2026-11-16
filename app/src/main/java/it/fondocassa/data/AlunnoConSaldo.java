package it.fondocassa.data;

import androidx.room.Embedded;

/** Alunno con il totale delle quote versate (in centesimi). */
public class AlunnoConSaldo {
    @Embedded
    public Alunno alunno;

    public long totaleVersato;
}
