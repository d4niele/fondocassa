package it.fondocassa.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Base64;

import it.fondocassa.sync.ApiModels.EntrataDto;
import it.fondocassa.sync.ApiModels.ExportResponse;
import it.fondocassa.sync.ApiModels.SyncRequest;
import it.fondocassa.sync.ApiModels.SyncResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class ApiClientTest {
    private MockWebServer server;
    private ApiClient api;

    @Before
    public void avvia() throws Exception {
        server = new MockWebServer();
        server.start();
        api = new ApiClient(server.url("/macros/s/ABC/exec").toString(), "segreto 123");
    }

    @After
    public void ferma() throws Exception {
        server.shutdown();
    }

    @Test
    public void pingInviaAzioneETokenCodificato() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        api.ping();

        RecordedRequest richiesta = server.takeRequest();
        assertEquals("GET", richiesta.getMethod());
        assertEquals("/macros/s/ABC/exec?action=ping&token=segreto+123", richiesta.getPath());
    }

    @Test
    public void syncInviaIRecordERiceveGliIdConfermati() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"timestamp_sync\":\"2026-11-16T10:00:00.000Z\","
                + "\"alunni\":[],\"quote\":[],\"entrate\":[\"e1\"],\"spese\":[]}"));
        SyncRequest richiesta = new SyncRequest();
        EntrataDto entrata = new EntrataDto();
        entrata.id = "e1";
        entrata.alunnoId = "a1";
        entrata.data = "2026-11-16";
        entrata.importoCent = 500;
        entrata.metodo = "Contante";
        richiesta.entrate.add(entrata);

        SyncResponse risposta = api.sync(richiesta);

        assertEquals("2026-11-16T10:00:00.000Z", risposta.timestampSync);
        assertEquals(1, risposta.entrate.size());
        RecordedRequest inviata = server.takeRequest();
        assertEquals("POST", inviata.getMethod());
        JsonObject corpo = JsonParser.parseString(inviata.getBody().readUtf8()).getAsJsonObject();
        assertEquals("sync", corpo.get("action").getAsString());
        assertEquals("segreto 123", corpo.get("token").getAsString());
        JsonObject e = corpo.getAsJsonArray("entrate").get(0).getAsJsonObject();
        assertEquals("e1", e.get("id_transazione").getAsString());
        assertEquals("a1", e.get("alunno_id").getAsString());
        assertEquals(500, e.get("importo_cent").getAsLong());
    }

    @Test
    public void laPostSegueIlRedirectDiAppsScriptInGet() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/echo?id=1").toString()));
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"url\":\"https://drive.google.com/file/d/X/view\"}"));

        String url = api.uploadGiustificativo("s1", "scontrino.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertEquals("https://drive.google.com/file/d/X/view", url);
        RecordedRequest post = server.takeRequest();
        assertEquals("POST", post.getMethod());
        JsonObject corpo = JsonParser.parseString(post.getBody().readUtf8()).getAsJsonObject();
        assertEquals("upload_giustificativo", corpo.get("action").getAsString());
        assertEquals("s1", corpo.get("id_spesa").getAsString());
        assertEquals("image/jpeg", corpo.get("mime_type").getAsString());
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                corpo.get("data_base64").getAsString());
        RecordedRequest seguito = server.takeRequest();
        assertEquals("GET", seguito.getMethod());
        assertEquals("/echo?id=1", seguito.getPath());
    }

    @Test
    public void exportAllDecodificaTutteLeTabelle() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ok\":true,"
                + "\"alunni\":[{\"alunno_id\":\"a1\",\"nome\":\"Anna Rossi\",\"attivo\":true}],"
                + "\"quote\":[{\"id_quota\":\"q1\",\"descrizione\":\"Quota di avvio\",\"importo_cent\":500,\"data\":\"2026-09-15\"}],"
                + "\"entrate\":[{\"id_transazione\":\"e1\",\"alunno_id\":\"a1\",\"data\":\"2026-09-20\","
                + "\"importo_cent\":500,\"metodo\":\"PayPal\",\"note\":\"\",\"timestamp_sync\":\"T\"}],"
                + "\"spese\":[{\"id_spesa\":\"s1\",\"descrizione\":\"Gessi\",\"categoria\":\"Materiale\","
                + "\"data\":\"2026-10-01\",\"importo_cent\":350,\"url_giustificativo\":\"https://d/x\"}]}"));

        ExportResponse export = api.exportAll();

        assertTrue(server.takeRequest().getPath().contains("action=export_all"));
        assertEquals("Anna Rossi", export.alunni.get(0).nome);
        assertEquals(500, export.quote.get(0).importoCent);
        assertEquals("PayPal", export.entrate.get(0).metodo);
        assertEquals("https://d/x", export.spese.get(0).urlGiustificativo);
    }

    @Test
    public void erroreApplicativoDelloScript() {
        server.enqueue(new MockResponse().setBody("{\"ok\":false,\"error\":\"Token non valido\"}"));
        ApiException e = assertThrows(ApiException.class, () -> api.ping());
        assertEquals("Token non valido", e.getMessage());
    }

    @Test
    public void rispostaHtmlInvecediJson() {
        server.enqueue(new MockResponse().setBody("<html>Accedi a Google</html>"));
        ApiException e = assertThrows(ApiException.class, () -> api.ping());
        assertTrue(e.getMessage().contains("Risposta non valida"));
    }

    @Test
    public void statoHttpDiErrore() {
        server.enqueue(new MockResponse().setResponseCode(404));
        ApiException e = assertThrows(ApiException.class, () -> api.ping());
        assertTrue(e.getMessage().contains("404"));
    }

    @Test
    public void uploadSenzaUrlNellaRisposta() {
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        assertThrows(ApiException.class, () -> api.uploadGiustificativo("s1", "f.pdf", "application/pdf", new byte[0]));
    }

    @Test
    public void troppiRedirect() {
        for (int i = 0; i < 10; i++) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/loop"));
        }
        ApiException e = assertThrows(ApiException.class, () -> api.ping());
        assertEquals("Troppi redirect", e.getMessage());
    }
}
