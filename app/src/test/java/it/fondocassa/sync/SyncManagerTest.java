package it.fondocassa.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import it.fondocassa.TestApp;
import it.fondocassa.data.Alunno;
import it.fondocassa.data.AppDatabase;
import it.fondocassa.data.Entrata;
import it.fondocassa.data.Fondo;
import it.fondocassa.data.Quota;
import it.fondocassa.data.Repository;
import it.fondocassa.data.Settings;
import it.fondocassa.data.Spesa;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

@RunWith(AndroidJUnit4.class)
public class SyncManagerTest {
    private static final String TS = "2026-11-16T10:00:00.000Z";

    private MockWebServer server;
    private AppDatabase db;
    private Repository repo;
    private Settings settings;
    private SyncManager sync;

    @Before
    public void avvia() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        server = new MockWebServer();
        server.start();
        db = TestApp.dbInMemoria(context);
        repo = new Repository(db, () -> { });
        settings = new Settings(context);
        settings.setConnessione(server.url("/exec").toString(), "token");
        sync = SyncManager.crea(db, settings);
    }

    @After
    public void ferma() throws Exception {
        server.shutdown();
        db.close();
    }

    private Alunno alunno(String nome) {
        Alunno a = new Alunno();
        a.nome = nome;
        repo.salvaAlunno(a);
        return a;
    }

    private Entrata entrata(Alunno a, long importo) {
        Entrata e = new Entrata();
        e.alunnoId = a.id;
        e.importo = importo;
        e.data = "2026-11-16";
        repo.salvaEntrata(e);
        return e;
    }

    private static String conferma(String alunni, String quote, String entrate, String spese) {
        return "{\"ok\":true,\"timestamp_sync\":\"" + TS + "\",\"alunni\":[" + alunni + "],\"quote\":[" + quote
                + "],\"entrate\":[" + entrate + "],\"spese\":[" + spese + "]}";
    }

    private static String q(String id) {
        return "\"" + id + "\"";
    }

    @Test
    public void inviaSoloIRecordNonSincronizzatiELiMarca() throws Exception {
        Alunno a = alunno("Anna");
        Quota quota = new Quota();
        quota.descrizione = "Quota di avvio";
        quota.importo = 500;
        repo.salvaQuota(quota);
        Entrata e = entrata(a, 500);
        server.enqueue(new MockResponse().setBody(conferma(q(a.id), q(quota.id), q(e.id), "")));

        SyncManager.Esito esito = sync.sincronizza();

        assertEquals(3, esito.inviati);
        assertTrue(db.alunni().get(a.id).synced);
        assertTrue(db.quote().get(quota.id).synced);
        assertTrue(db.entrate().get(e.id).synced);
        assertEquals(TS, db.entrate().get(e.id).timestampSync);
        assertEquals(TS, settings.ultimaSync());
        JsonObject corpo = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals(1, corpo.getAsJsonArray("alunni").size());
        assertEquals(500, corpo.getAsJsonArray("quote").get(0).getAsJsonObject().get("importo_cent").getAsLong());

        // Seconda sync: nulla da inviare, nessuna chiamata di rete.
        assertEquals(0, sync.sincronizza().inviati);
        assertEquals(1, server.getRequestCount());

        // Una nuova quota versata invia solo quella.
        Entrata seconda = entrata(a, 250);
        server.enqueue(new MockResponse().setBody(conferma("", "", q(seconda.id), "")));
        assertEquals(1, sync.sincronizza().inviati);
        corpo = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals(0, corpo.getAsJsonArray("alunni").size());
        assertEquals(1, corpo.getAsJsonArray("entrate").size());
    }

    @Test
    public void seLoScriptNonConfermaUnRecordRestaInAttesa() throws Exception {
        Alunno a = alunno("Anna");
        Entrata e = entrata(a, 500);
        server.enqueue(new MockResponse().setBody(conferma(q(a.id), "", "", "")));

        sync.sincronizza();

        assertTrue(db.alunni().get(a.id).synced);
        assertFalse(db.entrate().get(e.id).synced);
    }

    @Test
    public void senzaReteIRecordRestanoInAttesa() {
        Alunno a = alunno("Anna");
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertThrows(IOException.class, () -> sync.sincronizza());

        assertFalse(db.alunni().get(a.id).synced);
        assertEquals("", settings.ultimaSync());
    }

    @Test
    public void conTokenErratoNonVieneMarcatoNulla() {
        Alunno a = alunno("Anna");
        server.enqueue(new MockResponse().setBody("{\"ok\":false,\"error\":\"Token non valido\"}"));

        ApiException e = assertThrows(ApiException.class, () -> sync.sincronizza());

        assertEquals("Token non valido", e.getMessage());
        assertFalse(db.alunni().get(a.id).synced);
    }

    @Test
    public void ilGiustificativoVieneCaricatoPrimaELUrlFinisceNellaSpesa() throws Exception {
        File scontrino = File.createTempFile("scontrino", ".jpg");
        Files.write(scontrino.toPath(), new byte[]{9, 8, 7});
        Spesa s = new Spesa();
        s.descrizione = "Gessi";
        s.importo = 350;
        s.data = "2026-10-01";
        s.giustificativoLocale = scontrino.getAbsolutePath();
        s.giustificativoMime = "image/jpeg";
        repo.salvaSpesa(s);
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"url\":\"https://drive.google.com/file/d/F1/view\"}"));
        server.enqueue(new MockResponse().setBody(conferma("", "", "", q(s.id))));

        SyncManager.Esito esito = sync.sincronizza();

        assertEquals(1, esito.giustificativiCaricati);
        assertEquals(1, esito.inviati);
        JsonObject upload = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals("upload_giustificativo", upload.get("action").getAsString());
        assertEquals(s.id, upload.get("id_spesa").getAsString());
        assertEquals("CQgH", upload.get("data_base64").getAsString());
        JsonObject corpo = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals("https://drive.google.com/file/d/F1/view",
                corpo.getAsJsonArray("spese").get(0).getAsJsonObject().get("url_giustificativo").getAsString());

        Spesa salvata = db.spese().get(s.id);
        assertTrue(salvata.synced);
        assertEquals("https://drive.google.com/file/d/F1/view", salvata.urlGiustificativo);
        assertNull(salvata.giustificativoLocale);
        assertFalse("il file locale non serve più", scontrino.exists());
    }

    @Test
    public void unAllegatoRifiutatoNonBloccaIlRestoERestaInAttesa() throws Exception {
        Alunno a = alunno("Anna");
        File scontrino = File.createTempFile("scontrino", ".jpg");
        Files.write(scontrino.toPath(), new byte[]{9, 8, 7});
        Spesa s = new Spesa();
        s.descrizione = "Gessi";
        s.importo = 350;
        s.data = "2026-10-01";
        s.giustificativoLocale = scontrino.getAbsolutePath();
        s.giustificativoMime = "image/jpeg";
        repo.salvaSpesa(s);
        server.enqueue(new MockResponse().setBody("{\"ok\":false,\"error\":\"Accesso negato: DriveApp.\"}"));
        server.enqueue(new MockResponse().setBody(conferma(q(a.id), "", "", "")));

        SyncManager.Esito esito = sync.sincronizza();

        assertEquals(1, esito.inviati);
        assertEquals(0, esito.giustificativiCaricati);
        assertEquals(1, esito.giustificativiFalliti);
        assertEquals("Accesso negato: DriveApp.", esito.erroreGiustificativi);
        server.takeRequest();
        JsonObject corpo = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals(1, corpo.getAsJsonArray("alunni").size());
        assertEquals("la spesa non parte senza il suo allegato", 0, corpo.getAsJsonArray("spese").size());
        assertTrue(db.alunni().get(a.id).synced);
        Spesa salvata = db.spese().get(s.id);
        assertFalse(salvata.synced);
        assertTrue(salvata.uploadInAttesa());
        assertTrue("il file resta sul telefono per il prossimo tentativo", scontrino.exists());
    }

    @Test
    public void leCancellazioniVengonoPropagateEPoiRimosse() throws Exception {
        Alunno a = alunno("Anna");
        Entrata e = entrata(a, 500);
        server.enqueue(new MockResponse().setBody(conferma(q(a.id), "", q(e.id), "")));
        sync.sincronizza();
        server.takeRequest();

        repo.eliminaEntrata(e.id);
        server.enqueue(new MockResponse().setBody(conferma("", "", q(e.id), "")));
        sync.sincronizza();

        JsonObject corpo = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertTrue(corpo.getAsJsonArray("entrate").get(0).getAsJsonObject().get("eliminato").getAsBoolean());
        assertNull(db.entrate().get(e.id));
    }

    @Test
    public void laCancellazioneArrivaAlFoglioAncheSeLaConfermaDellInvioSiEPersa() throws Exception {
        Alunno a = alunno("Anna");
        Entrata e = entrata(a, 500);
        // Lo script riceve (e scrive) la riga, ma la risposta non arriva all'app.
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertThrows(IOException.class, () -> sync.sincronizza());
        server.takeRequest();

        repo.eliminaEntrata(e.id);
        server.enqueue(new MockResponse().setBody(conferma(q(a.id), "", q(e.id), "")));
        sync.sincronizza();

        JsonObject corpo = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        JsonObject inviata = corpo.getAsJsonArray("entrate").get(0).getAsJsonObject();
        assertEquals(e.id, inviata.get("id_transazione").getAsString());
        assertTrue(inviata.get("eliminato").getAsBoolean());
        assertNull(db.entrate().get(e.id));
    }

    @Test
    public void reinviaTuttoRimandaAncheIRecordGiaSincronizzati() throws Exception {
        Alunno a = alunno("Anna");
        Entrata e = entrata(a, 500);
        server.enqueue(new MockResponse().setBody(conferma(q(a.id), "", q(e.id), "")));
        sync.sincronizza();
        server.takeRequest();
        assertTrue(db.entrate().get(e.id).synced);

        server.enqueue(new MockResponse().setBody(conferma(q(a.id), "", q(e.id), "")));
        SyncManager.Esito esito = sync.reinviaTutto();

        assertEquals(2, esito.inviati);
        JsonObject corpo = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals(1, corpo.getAsJsonArray("alunni").size());
        JsonObject inviata = corpo.getAsJsonArray("entrate").get(0).getAsJsonObject();
        assertEquals(e.id, inviata.get("id_transazione").getAsString());
        assertFalse(inviata.get("eliminato").getAsBoolean());
        assertTrue(db.alunni().get(a.id).synced);
        assertTrue(db.entrate().get(e.id).synced);
    }

    @Test
    public void ilFondoInizialeVieneInviatoEConfermato() throws Exception {
        repo.salvaFondoIniziale(3750, "Avanzo", "2026-09-01");
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"timestamp_sync\":\"" + TS + "\","
                + "\"fondi\":[\"fondo_iniziale\"]}"));

        assertEquals(1, sync.sincronizza().inviati);

        JsonObject inviato = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        JsonObject fondo = inviato.getAsJsonArray("fondi").get(0).getAsJsonObject();
        assertEquals("fondo_iniziale", fondo.get("id_fondo").getAsString());
        assertEquals(3750, fondo.get("importo_cent").getAsLong());
        assertTrue(db.fondi().get(Fondo.ID_INIZIALE).synced);
    }

    @Test
    public void ilRipristinoDaUnoScriptPrecedenteNonCancellaIlFondoIniziale() throws Exception {
        repo.salvaFondoIniziale(3750, "Avanzo", "2026-09-01");
        // Lo script non ancora aggiornato non restituisce "fondi".
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"alunni\":[],\"quote\":[],"
                + "\"entrate\":[],\"spese\":[]}"));

        sync.ripristina();

        assertEquals(3750, db.fondi().totale());
    }

    @Test
    public void ilRipristinoRecuperaIlFondoIniziale() throws Exception {
        repo.salvaFondoIniziale(100, "Vecchio", "2026-09-01");
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"alunni\":[],\"quote\":[],"
                + "\"entrate\":[],\"spese\":[],\"fondi\":[{\"id_fondo\":\"fondo_iniziale\","
                + "\"descrizione\":\"Avanzo\",\"importo_cent\":3750,\"data\":\"2026-09-01\"}]}"));

        assertEquals(1, sync.ripristina());

        Fondo fondo = db.fondi().get(Fondo.ID_INIZIALE);
        assertEquals(3750, fondo.importo);
        assertTrue(fondo.synced);
    }

    @Test
    public void ilRipristinoSostituisceTuttiIDatiLocali() throws Exception {
        Alunno vecchio = alunno("Dato locale da scartare");
        server.enqueue(new MockResponse().setBody("{\"ok\":true,"
                + "\"alunni\":[{\"alunno_id\":\"a1\",\"nome\":\"Anna Rossi\",\"attivo\":true},"
                + "{\"alunno_id\":\"a2\",\"nome\":\"Marco Verdi\",\"attivo\":false}],"
                + "\"quote\":[{\"id_quota\":\"q1\",\"descrizione\":\"Quota di avvio\",\"importo_cent\":500,\"data\":\"2026-09-15\"}],"
                + "\"entrate\":[{\"id_transazione\":\"e1\",\"alunno_id\":\"a1\",\"data\":\"2026-09-20\","
                + "\"importo_cent\":500,\"metodo\":\"Bonifico\",\"note\":\"ok\",\"timestamp_sync\":\"" + TS + "\"}],"
                + "\"spese\":[{\"id_spesa\":\"s1\",\"descrizione\":\"Gessi\",\"categoria\":\"Materiale\","
                + "\"data\":\"2026-10-01\",\"importo_cent\":350,\"url_giustificativo\":\"https://d/x\"}]}"));

        int importati = sync.ripristina();

        assertEquals(5, importati);
        assertNull(db.alunni().get(vecchio.id));
        assertEquals(2, db.alunni().list().size());
        assertFalse(db.alunni().get("a2").attivo);
        assertEquals(500, db.quote().totale());
        Entrata e = db.entrate().get("e1");
        assertEquals("Bonifico", e.metodo);
        assertEquals(500, e.importo);
        assertTrue(e.synced);
        assertEquals("https://d/x", db.spese().get("s1").urlGiustificativo);
        assertEquals(500, db.alunni().listConSaldo().get(0).totaleVersato);
        // Dopo il ripristino non c'è nulla da reinviare.
        assertEquals(0, sync.sincronizza().inviati);
    }

    @Test
    public void ilBackupSincronizzaEPoiInviaIlFileDelDatabase() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AppDatabase suFile = androidx.room.Room.databaseBuilder(context, AppDatabase.class, "backup-test.db")
                .allowMainThreadQueries().build();
        try {
            Repository repoSuFile = new Repository(suFile, () -> { });
            Alunno a = new Alunno();
            a.nome = "Anna Rossi";
            repoSuFile.salvaAlunno(a);
            server.enqueue(new MockResponse().setBody(conferma(q(a.id), "", "", "")));
            server.enqueue(new MockResponse().setBody("{\"ok\":true,\"url\":\"https://drive.google.com/file/d/Z/view\","
                    + "\"nome\":\"fondocassa-backup-20261116-100000.zip\",\"giustificativi\":3}"));

            ApiModels.BackupResponse backup = SyncManager.crea(suFile, settings)
                    .esportaBackup(context.getDatabasePath("backup-test.db"));

            assertEquals("https://drive.google.com/file/d/Z/view", backup.url);
            assertEquals(3, backup.giustificativi);
            assertEquals("sync", JsonParser.parseString(server.takeRequest().getBody().readUtf8())
                    .getAsJsonObject().get("action").getAsString());
            JsonObject corpo = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
            assertEquals("backup", corpo.get("action").getAsString());
            byte[] db = java.util.Base64.getDecoder().decode(corpo.get("db_base64").getAsString());
            assertEquals("SQLite format 3", new String(db, 0, 15, java.nio.charset.StandardCharsets.US_ASCII));
            // Il file inviato contiene davvero i dati (nessuna scrittura rimasta nel journal).
            assertTrue(new String(db, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Anna Rossi"));
        } finally {
            suFile.close();
        }
    }

    @Test
    public void seLaSincronizzazioneFallisceIlBackupNonParte() {
        alunno("Anna");
        server.enqueue(new MockResponse().setBody("{\"ok\":false,\"error\":\"Token non valido\"}"));

        assertThrows(ApiException.class, () -> sync.esportaBackup(new File("inesistente.db")));

        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void seIlRipristinoFallisceIDatiLocaliRestanoIntatti() {
        Alunno a = alunno("Anna");
        server.enqueue(new MockResponse().setBody("{\"ok\":false,\"error\":\"Token non valido\"}"));

        assertThrows(ApiException.class, () -> sync.ripristina());

        assertEquals("Anna", db.alunni().get(a.id).nome);
    }
}
