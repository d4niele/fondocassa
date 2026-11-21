package it.fondocassa.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import it.fondocassa.TestApp;
import it.fondocassa.data.Alunno;
import it.fondocassa.data.AppDatabase;
import it.fondocassa.data.Entrata;
import it.fondocassa.data.Quota;
import it.fondocassa.data.Repository;
import it.fondocassa.data.Spesa;
import it.fondocassa.sync.ApiClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

@RunWith(AndroidJUnit4.class)
public class RapportoTest {
    private static final LocalDateTime ADESSO = LocalDateTime.of(2026, 11, 21, 18, 30);

    private Context context;
    private AppDatabase db;
    private Repository repo;
    private File cartella;

    @Before
    public void prepara() {
        context = ApplicationProvider.getApplicationContext();
        db = TestApp.dbInMemoria(context);
        repo = new Repository(db, () -> { });
        cartella = new File(context.getCacheDir(), "test-pdf");
    }

    @After
    public void chiudi() {
        db.close();
    }

    private Spesa spesa(String descrizione, String data, long importo, String url, String locale) {
        Spesa s = new Spesa();
        s.descrizione = descrizione;
        s.data = data;
        s.importo = importo;
        s.urlGiustificativo = url;
        s.giustificativoLocale = locale;
        repo.salvaSpesa(s);
        return s;
    }

    private void datiDiEsempio() {
        Alunno anna = new Alunno();
        anna.nome = "Rossi Anna";
        repo.salvaAlunno(anna);
        Alunno marco = new Alunno();
        marco.nome = "Verdi Marco";
        repo.salvaAlunno(marco);
        Quota q = new Quota();
        q.descrizione = "Quota di inizio anno";
        q.importo = 2000;
        q.data = "2026-09-15";
        repo.salvaQuota(q);
        Entrata e = new Entrata();
        e.alunnoId = anna.id;
        e.importo = 2000;
        e.data = "2026-09-20";
        repo.salvaEntrata(e);
        repo.salvaFondoIniziale(3750, "Avanzo a.s. 2025/26", "2026-09-01");
    }

    @Test
    public void iGiustificativiSonoNumeratiNellOrdineDelleSpese() {
        datiDiEsempio();
        Spesa pullman = spesa("Pullman", "2026-10-28", 16500, "https://drive/1", null);
        spesa("Fazzoletti", "2026-11-05", 1290, "", null);
        Spesa cartoncini = spesa("Cartoncini", "2026-09-25", 1850, "https://drive/2", null);

        Rapporto r = Rapporto.leggi(db, "1ª C", "1.3.0", ADESSO);

        assertEquals("le spese sono in ordine di data", "Cartoncini", r.spese.get(0).descrizione);
        assertEquals(2, r.conGiustificativo.size());
        assertEquals(1, r.numeroAllegato(r.spese.get(0)));
        assertEquals(cartoncini.id, r.conGiustificativo.get(0).id);
        assertEquals(pullman.id, r.conGiustificativo.get(1).id);
        assertEquals(0, r.numeroAllegato(r.spese.get(2)));
    }

    @Test
    public void ilBilancioEQuelloDelRiepilogo() {
        datiDiEsempio();
        spesa("Cartoncini", "2026-09-25", 1850, "", null);

        Rapporto r = Rapporto.leggi(db, "", "1.3.0", ADESSO);

        assertEquals(2000, r.dovutoPerAlunno);
        assertEquals(3750 + 2000 - 1850, r.bilancio.cassaReale);
        assertEquals(2000, r.bilancio.daVersare);
        assertEquals(1, r.bilancio.alunniInRegola);
        assertNotNull(r.fondo);
    }

    @Test
    public void titoloENomeDelFileSonoDatati() {
        Rapporto conClasse = Rapporto.leggi(db, "1ª C", "1.3.0", ADESSO);
        assertEquals("Fondo cassa 1ª C", conClasse.titolo());
        assertEquals("21/11/2026 18:30", conClasse.creatoIl);
        assertEquals("Fondo cassa 1ª C - 2026-11-21.pdf", conClasse.nomeFile());

        Rapporto senza = Rapporto.leggi(db, "", "1.3.0", ADESSO);
        assertEquals("Fondo cassa - 2026-11-21.pdf", senza.nomeFile());
        assertNull("senza importo il fondo iniziale non compare", senza.fondo);
        assertEquals("Fondo cassa 3-B - 2026-11-21.pdf", Rapporto.leggi(db, "3/B", "1.3.0", ADESSO).nomeFile());
        assertEquals("Creato con Fondo cassa 1.3.0 · github.com/d4niele/fondocassa", senza.piede());
    }

    @Test
    public void ilTestoVaACapoTraLeParoleESpezzaQuelleTroppoLunghe() {
        Paint p = new Paint();
        p.setTextSize(10);
        float larghezza = p.measureText("Pullman gita");
        List<String> righe = Impaginatore.righe("Pullman gita al museo", p, larghezza);
        assertEquals(List.of("Pullman gita", "al museo"), righe);

        String link = "https://drive.google.com/file/d/abcdefghijklmnopqrstuvwxyz/view";
        for (String riga : Impaginatore.righe(link, p, larghezza)) {
            assertTrue(riga, p.measureText(riga) <= larghezza);
        }
        assertEquals(link, String.join("", Impaginatore.righe(link, p, larghezza)));
        assertEquals(List.of("a", "", "b"), Impaginatore.righe("a\n\nb", p, 200));
    }

    @Test
    public void unGiustificativoSulTelefonoSiUsaSenzaScaricarlo() throws Exception {
        File locale = new File(context.getFilesDir(), "scontrino.jpg");
        Files.write(locale.toPath(), new byte[] {1, 2, 3});
        Spesa s = spesa("Gessi", "2026-10-01", 350, "", locale.getPath());
        s.giustificativoMime = "image/jpeg";

        PdfRapporto.Allegato a = new EsportaPdf.Fonte(null, cartella).trova(s);

        assertNull(a.problema);
        assertEquals(locale, a.file);
        assertEquals("image/jpeg", a.mime);
    }

    @Test
    public void senzaCollegamentoIlGiustificativoSuDriveESpiegatoNonInventato() {
        Spesa s = spesa("Gessi", "2026-10-01", 350, "https://drive/1", null);
        PdfRapporto.Allegato a = new EsportaPdf.Fonte(null, cartella).trova(s);
        assertNull(a.file);
        assertTrue(a.problema, a.problema.contains("collegamento"));
    }

    @Test
    public void ilGiustificativoSuDriveVieneScaricatoEPoiCancellato() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            String pdf = Base64.getEncoder().encodeToString("%PDF-1.4 finto".getBytes(StandardCharsets.UTF_8));
            server.enqueue(new MockResponse().setBody("{\"ok\":true,\"nome\":\"s1_ricevuta.pdf\","
                    + "\"mime_type\":\"application/pdf\",\"data_base64\":\"" + pdf + "\"}"));
            Spesa s = spesa("Pullman", "2026-10-28", 16500, "https://drive/1", null);
            EsportaPdf.Fonte fonte = new EsportaPdf.Fonte(
                    new ApiClient(server.url("/exec").toString(), "token"), cartella);

            PdfRapporto.Allegato a = fonte.trova(s);

            assertNull(a.problema);
            assertEquals("application/pdf", a.mime);
            assertEquals("%PDF-1.4 finto", new String(Files.readAllBytes(a.file.toPath()), StandardCharsets.UTF_8));
            String richiesta = server.takeRequest().getBody().readUtf8();
            assertTrue(richiesta, richiesta.contains("\"action\":\"scarica_giustificativo\""));
            assertTrue(richiesta, richiesta.contains("\"id_spesa\":\"" + s.id + "\""));

            fonte.pulisci();
            assertTrue("i file scaricati non restano sul telefono", !a.file.exists());
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void senzaReteNonSiRiprovaAOgniGiustificativo() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            EsportaPdf.Fonte fonte = new EsportaPdf.Fonte(
                    new ApiClient(server.url("/exec").toString(), "token"), cartella);
            Spesa a = spesa("Pullman", "2026-10-28", 16500, "https://drive/1", null);
            Spesa b = spesa("Museo", "2026-10-28", 4400, "https://drive/2", null);

            assertTrue(fonte.trova(a).problema.contains("connessione"));
            assertTrue(fonte.trova(b).problema.contains("connessione"));
            assertEquals(1, server.getRequestCount());
        } finally {
            server.shutdown();
        }
    }

    /** Pagine in memoria che ricordano i testi e le immagini disegnati. */
    private static final class PagineDiProva implements Impaginatore.Supporto {
        final List<String> testi = new ArrayList<>();
        int pagine;
        int immagini;
        boolean scritto;
        boolean chiuso;

        @Override
        public Canvas nuovaPagina(int numero, int larghezza, int altezza) {
            pagine++;
            return new Canvas(Bitmap.createBitmap(larghezza, altezza, Bitmap.Config.ARGB_8888)) {
                @Override
                public void drawText(String testo, float x, float y, Paint paint) {
                    testi.add(testo);
                }

                @Override
                public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
                    immagini++;
                }
            };
        }

        @Override
        public void chiudiPagina() {
        }

        @Override
        public void scrivi(OutputStream out) throws IOException {
            scritto = true;
            out.write("%PDF".getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void chiudi() {
            chiuso = true;
        }

        boolean contiene(String parte) {
            for (String t : testi) {
                if (t.contains(parte)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // per un JPEG vero da decodificare
    public void ilRendicontoContieneTutteLeSezioniEIGiustificativi() throws Exception {
        datiDiEsempio();
        File foto = new File(context.getFilesDir(), "scontrino.jpg");
        try (OutputStream out = new FileOutputStream(foto)) {
            Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out);
        }
        Spesa cartoncini = spesa("Cartoncini", "2026-09-25", 1850, "", foto.getPath());
        cartoncini.giustificativoMime = "image/jpeg";
        db.spese().upsert(cartoncini);
        spesa("Pullman", "2026-10-28", 16500, "https://drive.google.com/file/d/1/view", null);
        Rapporto r = Rapporto.leggi(db, "1ª C", "1.3.0", ADESSO);
        PagineDiProva pagine = new PagineDiProva();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        PdfRapporto.Esito esito = PdfRapporto.scrivi(r, new EsportaPdf.Fonte(null, cartella), out, pagine);

        assertTrue(pagine.scritto);
        assertTrue(pagine.chiuso);
        assertEquals(pagine.pagine, esito.pagine);
        assertTrue("rendiconto più una pagina per allegato", esito.pagine >= 3);
        assertEquals(1, esito.allegatiInclusi);
        assertEquals(1, esito.allegatiMancanti);
        assertEquals("la foto dello scontrino è nel PDF", 1, pagine.immagini);
        for (String parte : new String[] {"Fondo cassa 1ª C", "Rendiconto al 21/11/2026 18:30", "Riepilogo",
                "In cassa adesso", "Quote", "Alunni", "Entrate", "Spese", "Giustificativi", "Allegato 1 · Cartoncini",
                "Allegato 2 · Pullman", "Rossi Anna", "Quota di inizio anno", "Avanzo a.s. 2025/26",
                "Creato con Fondo cassa 1.3.0 · github.com/d4niele/fondocassa", "Pagina 1"}) {
            assertTrue("manca «" + parte + "»", pagine.contiene(parte));
        }
        assertTrue("l'allegato mancante rimanda a Drive", pagine.contiene("Si trova su Google Drive"));
    }

    @Test
    public void unErroreChiudeIlDocumentoSenzaScriverlo() {
        Rapporto r = Rapporto.leggi(db, "", "1.3.0", ADESSO);
        PagineDiProva pagine = new PagineDiProva();
        OutputStream rotto = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disco pieno");
            }
        };

        assertThrows(IOException.class,
                () -> PdfRapporto.scrivi(r, new EsportaPdf.Fonte(null, cartella), rotto, pagine));
        assertTrue(pagine.chiuso);
    }
}
