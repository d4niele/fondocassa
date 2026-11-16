package it.fondocassa.sync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import it.fondocassa.sync.ApiModels.ExportResponse;
import it.fondocassa.sync.ApiModels.SyncRequest;
import it.fondocassa.sync.ApiModels.SyncResponse;
import it.fondocassa.sync.ApiModels.UploadRequest;

/**
 * Client HTTP verso la Web App di Google Apps Script.
 *
 * <p>Apps Script risponde sempre HTTP 200 con un JSON {@code {"ok": true|false, ...}} e, per le
 * POST, passa da un redirect 302 verso script.googleusercontent.com che va seguito in GET:
 * i redirect sono quindi gestiti a mano.
 */
public class ApiClient {
    private static final int MAX_REDIRECT = 5;
    private static final int TIMEOUT_CONNESSIONE_MS = 15_000;
    private static final int TIMEOUT_LETTURA_MS = 60_000;

    private final String endpoint;
    private final String token;
    private final Gson gson = new Gson();

    public ApiClient(String endpoint, String token) {
        this.endpoint = endpoint;
        this.token = token;
    }

    /** Verifica che endpoint e token siano corretti. */
    public void ping() throws IOException {
        get("ping");
    }

    public SyncResponse sync(SyncRequest richiesta) throws IOException {
        richiesta.token = token;
        return gson.fromJson(post(gson.toJson(richiesta)), SyncResponse.class);
    }

    /** Carica un giustificativo su Drive e restituisce l'URL condivisibile. */
    public String uploadGiustificativo(String idSpesa, String filename, String mimeType, byte[] contenuto)
            throws IOException {
        UploadRequest richiesta = new UploadRequest();
        richiesta.token = token;
        richiesta.idSpesa = idSpesa;
        richiesta.filename = filename;
        richiesta.mimeType = mimeType;
        richiesta.dataBase64 = Base64.getEncoder().encodeToString(contenuto);
        JsonObject risposta = post(gson.toJson(richiesta));
        JsonElement url = risposta.get("url");
        if (url == null || !url.isJsonPrimitive() || url.getAsString().isEmpty()) {
            throw new ApiException("Lo script non ha restituito l'URL del giustificativo");
        }
        return url.getAsString();
    }

    public ExportResponse exportAll() throws IOException {
        return gson.fromJson(get("export_all"), ExportResponse.class);
    }

    private JsonObject get(String action) throws IOException {
        String separatore = endpoint.contains("?") ? "&" : "?";
        String url = endpoint + separatore + "action=" + action
                + "&token=" + URLEncoder.encode(token, "UTF-8");
        return esegui(url, null);
    }

    private JsonObject post(String json) throws IOException {
        return esegui(endpoint, json.getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject esegui(String indirizzo, byte[] corpo) throws IOException {
        URL url;
        try {
            url = new URL(indirizzo);
        } catch (IOException e) {
            throw new ApiException("URL dello script non valido", e);
        }
        for (int salto = 0; salto <= MAX_REDIRECT; salto++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(TIMEOUT_CONNESSIONE_MS);
                conn.setReadTimeout(TIMEOUT_LETTURA_MS);
                conn.setRequestProperty("Accept", "application/json");
                if (corpo != null) {
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    // text/plain evita il preflight e viene accettato da Apps Script in e.postData.contents
                    conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                    conn.setFixedLengthStreamingMode(corpo.length);
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(corpo);
                    }
                }
                int stato = conn.getResponseCode();
                if (stato == 301 || stato == 302 || stato == 303 || stato == 307 || stato == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) {
                        throw new ApiException("Redirect senza destinazione (HTTP " + stato + ")");
                    }
                    url = new URL(url, location);
                    if (stato != 307 && stato != 308) {
                        corpo = null; // come i browser: dopo 301/302/303 si prosegue in GET
                    }
                    continue;
                }
                if (stato != 200) {
                    throw new ApiException("Lo script ha risposto HTTP " + stato
                            + ": controlla l'URL e che la Web App sia pubblicata con accesso \"Chiunque\"");
                }
                try (InputStream in = conn.getInputStream()) {
                    return interpreta(leggi(in));
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new ApiException("Troppi redirect");
    }

    private static JsonObject interpreta(String testo) throws ApiException {
        JsonObject json;
        try {
            json = JsonParser.parseString(testo).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            // Tipicamente una pagina HTML di login/errore di Google.
            throw new ApiException("Risposta non valida dallo script: controlla l'URL della Web App", e);
        }
        JsonElement ok = json.get("ok");
        if (ok == null || !ok.isJsonPrimitive() || !ok.getAsBoolean()) {
            JsonElement errore = json.get("error");
            throw new ApiException(errore != null && errore.isJsonPrimitive()
                    ? errore.getAsString() : "Errore sconosciuto dallo script");
        }
        return json;
    }

    private static String leggi(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] blocco = new byte[8192];
        int letti;
        while ((letti = in.read(blocco)) != -1) {
            buffer.write(blocco, 0, letti);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
