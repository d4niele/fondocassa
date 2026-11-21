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

    public void setClasse(String classe) {
        prefs.edit().putString(CLASSE, classe).apply();
    }

    /** Timestamp ISO dell'ultima sincronizzazione riuscita, o stringa vuota. */
    public String ultimaSync() {
        return prefs.getString(ULTIMA_SYNC, "");
    }

    public void setUltimaSync(String timestamp) {
        prefs.edit().putString(ULTIMA_SYNC, timestamp).apply();
    }
}
