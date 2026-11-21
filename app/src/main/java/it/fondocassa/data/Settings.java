package it.fondocassa.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Configurazione dell'app (endpoint Apps Script, token, PIN). Vive nella sandbox privata. */
public class Settings {
    private static final String ENDPOINT = "endpoint";
    private static final String TOKEN = "token";
    private static final String PIN = "pin_hash";
    private static final String BIOMETRIA = "biometria";
    private static final String ULTIMA_SYNC = "ultima_sync";
    private static final String CLASSE = "classe";
    private static final String CLASSE_DA_INVIARE = "classe_da_inviare";
    private static final String CLASSE_VERSIONE = "classe_versione";

    /** Chiave della classe nel foglio Sync_Impostazioni. */
    public static final String CHIAVE_CLASSE = "classe";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("fondocassa", Context.MODE_PRIVATE);
    }

    public String endpoint() {
        return prefs.getString(ENDPOINT, "");
    }

    public String token() {
        return prefs.getString(TOKEN, "");
    }

    public void setConnessione(String endpoint, String token) {
        prefs.edit().putString(ENDPOINT, endpoint.trim()).putString(TOKEN, token.trim()).apply();
    }

    public boolean connessioneConfigurata() {
        return !endpoint().isEmpty() && !token().isEmpty();
    }

    public String pinHash() {
        return prefs.getString(PIN, null);
    }

    public void setPinHash(String hash) {
        prefs.edit().putString(PIN, hash).apply();
    }

    public boolean biometria() {
        return prefs.getBoolean(BIOMETRIA, false);
    }

    public void setBiometria(boolean attiva) {
        prefs.edit().putBoolean(BIOMETRIA, attiva).apply();
    }

    /** La classe mostrata nel titolo (es. «1ª C»), o stringa vuota. */
    public String classe() {
        return prefs.getString(CLASSE, "");
    }

    /** Imposta la classe; se è cambiata, la segna da inviare al foglio. @return true se è cambiata */
    public boolean setClasse(String classe) {
        if (classe.equals(classe())) {
            return false;
        }
        prefs.edit().putString(CLASSE, classe).putBoolean(CLASSE_DA_INVIARE, true)
                .putLong(CLASSE_VERSIONE, versioneClasse() + 1).apply();
        return true;
    }

    public boolean classeDaInviare() {
        // Una classe impostata prima che venisse sincronizzata non ha l'indicatore: va inviata.
        return prefs.getBoolean(CLASSE_DA_INVIARE, !classe().isEmpty());
    }

    /** Cresce a ogni modifica: una conferma del foglio vale solo per la versione che è stata inviata. */
    public long versioneClasse() {
        return prefs.getLong(CLASSE_VERSIONE, 0);
    }

    /** Il foglio ha ricevuto la classe: non va più inviata, a meno che nel frattempo sia cambiata. */
    public void confermaClasse(long versioneInviata) {
        if (versioneInviata == versioneClasse()) {
            prefs.edit().putBoolean(CLASSE_DA_INVIARE, false).apply();
        }
    }

    /** Rimette in attesa la classe, se c'è: la prossima sincronizzazione la riscrive sul foglio. */
    public void marcaClasseDaInviare() {
        if (!classe().isEmpty()) {
            prefs.edit().putBoolean(CLASSE_DA_INVIARE, true).apply();
        }
    }

    /** Classe letta dal foglio durante il ripristino: è già allineata, non va reinviata. */
    public void ripristinaClasse(String classe) {
        prefs.edit().putString(CLASSE, classe).putBoolean(CLASSE_DA_INVIARE, false)
                .putLong(CLASSE_VERSIONE, versioneClasse() + 1).apply();
    }

    /** Timestamp ISO dell'ultima sincronizzazione riuscita, o stringa vuota. */
    public String ultimaSync() {
        return prefs.getString(ULTIMA_SYNC, "");
    }

    public void setUltimaSync(String timestamp) {
        prefs.edit().putString(ULTIMA_SYNC, timestamp).apply();
    }
}
