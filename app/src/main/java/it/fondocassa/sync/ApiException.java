package it.fondocassa.sync;

import java.io.IOException;

/** Errore restituito dallo script (token errato, azione sconosciuta…) o risposta non interpretabile. */
public class ApiException extends IOException {
    public ApiException(String messaggio) {
        super(messaggio);
    }

    public ApiException(String messaggio, Throwable causa) {
        super(messaggio, causa);
    }
}
