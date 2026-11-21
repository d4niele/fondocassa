package it.fondocassa.report;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import it.fondocassa.data.AlunnoConSaldo;
import it.fondocassa.data.EntrataConAlunno;
import it.fondocassa.data.Quota;
import it.fondocassa.data.Spesa;
import it.fondocassa.util.Bilancio;
import it.fondocassa.util.Date;
import it.fondocassa.util.Money;
import it.fondocassa.util.StatoPagamento;

/**
 * Il rendiconto completo in PDF: riepilogo, quote, alunni, entrate, spese e, in fondo, i
 * giustificativi (le foto come immagini, i PDF pagina per pagina).
 */
public final class PdfRapporto {
    /** Lato massimo, in pixel, delle immagini messe nel PDF: leggibili senza appesantire il file. */
    private static final int LATO_IMMAGINE = 1200;
    /** Larghezza, in pixel, con cui si disegnano le pagine dei giustificativi in PDF. */
    private static final int LARGHEZZA_PAGINA_ALLEGATO = 1000;

    /** Il file di un giustificativo, o il motivo per cui non è disponibile. */
    public static final class Allegato {
        final File file;
        final String mime;
        final String problema;

        private Allegato(File file, String mime, String problema) {
            this.file = file;
            this.mime = mime;
            this.problema = problema;
        }

        public static Allegato file(File file, String mime) {
            return new Allegato(file, mime == null ? "" : mime, null);
        }

        public static Allegato mancante(String problema) {
            return new Allegato(null, "", problema);
        }
    }

    /** Procura il giustificativo di una spesa: dal telefono o da Drive. */
    public interface FonteAllegati {
        Allegato trova(Spesa spesa);
    }

    /** Esito della creazione del PDF. */
    public static final class Esito {
        public final int pagine;
        public final int allegatiInclusi;
        public final int allegatiMancanti;

        Esito(int pagine, int inclusi, int mancanti) {
            this.pagine = pagine;
            this.allegatiInclusi = inclusi;
            this.allegatiMancanti = mancanti;
        }
    }

    private PdfRapporto() {
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static Esito scrivi(Rapporto r, FonteAllegati fonte, OutputStream out) throws IOException {
        return scrivi(r, fonte, out, new Impaginatore.SupportoPdf());
    }

    static Esito scrivi(Rapporto r, FonteAllegati fonte, OutputStream out, Impaginatore.Supporto supporto)
            throws IOException {
        Impaginatore p = new Impaginatore(supporto, r.titolo() + " · Rendiconto al " + r.creatoIl, r.piede());
        try {
            copertina(p, r);
            riepilogo(p, r);
            quote(p, r);
            alunni(p, r);
            entrate(p, r);
            spese(p, r);
            int[] conteggi = giustificativi(p, r, fonte);
            p.scrivi(out);
            return new Esito(p.pagine(), conteggi[0], conteggi[1]);
        } catch (IOException | RuntimeException e) {
            p.abbandona();
            throw e;
        }
    }

    private static void copertina(Impaginatore p, Rapporto r) {
        p.paragrafo(r.titolo(), p.titolo);
        p.paragrafo("Rendiconto al " + r.creatoIl, p.sottotitolo);
        p.spazio(4);
        p.paragrafo("I dati sono quelli registrati nell'app in questo momento. Gli importi sono in euro.",
                p.piccolo);
    }

    private static void riepilogo(Impaginatore p, Rapporto r) {
        Bilancio b = r.bilancio;
        p.sezione("Riepilogo");
        p.voce("In cassa adesso", Money.format(b.cassaReale),
                "Fondo iniziale + versato − spese: i soldi che ci sono davvero", true);
        p.voce("Cassa virtuale", Money.format(b.cassaVirtuale),
                "Quanto ci sarebbe se tutti avessero versato le quote", false);
        p.voce("Fondo iniziale", Money.format(b.fondoIniziale),
                r.fondo == null ? "Nessun avanzo dagli anni passati" : descrizioneFondo(r), false);
        p.voce("Versato", Money.format(b.versato), r.entrate.size() + " versamenti", false);
        p.voce("Spese", Money.format(b.spese), r.spese.size() + " uscite, "
                + r.conGiustificativo.size() + " con giustificativo", false);
        p.voce("Quota a testa", Money.format(r.dovutoPerAlunno), r.quote.size() + " quote richieste", false);
        p.voce("Previsto", Money.format(b.previsto),
                "Quota a testa × " + b.alunniAttivi + " alunni attivi", false);
        p.voce("Da versare", Money.format(b.daVersare), "Quanto manca, sommando il residuo di ogni alunno", false);
        p.voce("Alunni in regola", b.alunniInRegola + " su " + b.alunniAttivi, null, false);
    }

    private static String descrizioneFondo(Rapporto r) {
        String d = r.fondo.descrizione == null ? "" : r.fondo.descrizione.trim();
        String data = Date.mostra(r.fondo.data);
        return (d.isEmpty() ? "Avanzo degli anni passati" : d) + (data.isEmpty() ? "" : ", dal " + data);
    }

    private static void quote(Impaginatore p, Rapporto r) {
        p.sezione("Quote");
        if (r.quote.isEmpty()) {
            p.paragrafo("Nessuna quota.", p.testo);
            return;
        }
        List<String[]> righe = new ArrayList<>();
        for (Quota q : r.quote) {
            righe.add(new String[] {Date.mostra(q.data), nz(q.descrizione), Money.format(q.importo)});
        }
        p.tabella(new Impaginatore.Colonna[] {
                new Impaginatore.Colonna("Data", 1.45f, false),
                new Impaginatore.Colonna("Descrizione", 3.4f, false),
                new Impaginatore.Colonna("A testa", 1.55f, true)},
                righe, new String[] {"", "Totale a testa", Money.format(r.dovutoPerAlunno)});
    }

    private static void alunni(Impaginatore p, Rapporto r) {
        p.sezione("Alunni");
        if (r.alunni.isEmpty()) {
            p.paragrafo("Nessun alunno.", p.testo);
            return;
        }
        List<String[]> righe = new ArrayList<>();
        long versato = 0;
        long residui = 0;
        for (AlunnoConSaldo a : r.alunni) {
            long residuo = a.alunno.attivo ? StatoPagamento.residuo(a.totaleVersato, r.dovutoPerAlunno) : 0;
            versato += a.totaleVersato;
            residui += residuo;
            String manca = !a.alunno.attivo ? "non attivo" : residuo == 0 ? "in regola" : Money.format(residuo);
            righe.add(new String[] {dettaglio(nz(a.alunno.nome), nz(a.alunno.note)),
                    Money.format(a.totaleVersato), manca});
        }
        p.tabella(new Impaginatore.Colonna[] {
                new Impaginatore.Colonna("Alunno", 3.4f, false),
                new Impaginatore.Colonna("Versato", 1.5f, true),
                new Impaginatore.Colonna("Manca", 1.5f, true)},
                righe, new String[] {r.alunni.size() + " alunni", Money.format(versato), Money.format(residui)});
    }

    private static void entrate(Impaginatore p, Rapporto r) {
        p.sezione("Entrate");
        if (r.entrate.isEmpty()) {
            p.paragrafo("Nessun versamento.", p.testo);
            return;
        }
        List<String[]> righe = new ArrayList<>();
        for (EntrataConAlunno e : r.entrate) {
            String nome = e.nomeAlunno != null ? e.nomeAlunno : "Alunno sconosciuto";
            righe.add(new String[] {Date.mostra(e.entrata.data),
                    dettaglio(nome, unisci(nz(e.entrata.metodo), nz(e.entrata.note))),
                    Money.format(e.entrata.importo)});
        }
        p.tabella(new Impaginatore.Colonna[] {
                new Impaginatore.Colonna("Data", 1.45f, false),
                new Impaginatore.Colonna("Alunno", 3.4f, false),
                new Impaginatore.Colonna("Importo", 1.55f, true)},
                righe, new String[] {"", r.entrate.size() + " versamenti", Money.format(r.bilancio.versato)});
    }

    private static void spese(Impaginatore p, Rapporto r) {
        p.sezione("Spese");
        if (r.spese.isEmpty()) {
            p.paragrafo("Nessuna spesa.", p.testo);
            return;
        }
        List<String[]> righe = new ArrayList<>();
        for (Spesa s : r.spese) {
            int n = r.numeroAllegato(s);
            righe.add(new String[] {Date.mostra(s.data),
                    dettaglio(nz(s.descrizione), unisci(nz(s.categoria), n > 0 ? "Allegato " + n : "")),
                    Money.format(s.importo)});
        }
        p.tabella(new Impaginatore.Colonna[] {
                new Impaginatore.Colonna("Data", 1.45f, false),
                new Impaginatore.Colonna("Descrizione", 3.4f, false),
                new Impaginatore.Colonna("Importo", 1.55f, true)},
                righe, new String[] {"", r.spese.size() + " uscite", Money.format(r.bilancio.spese)});
        if (!r.conGiustificativo.isEmpty()) {
            p.spazio(4);
            p.paragrafo("I giustificativi sono in fondo al documento, con lo stesso numero di «Allegato».",
                    p.piccolo);
        }
    }

    /** Testo di una cella con un dettaglio in piccolo sotto (vedi {@link Impaginatore#tabella}). */
    private static String dettaglio(String principale, String sotto) {
        return sotto.isEmpty() ? principale : principale + "\n" + sotto;
    }

    private static String unisci(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + " · " + b;
    }

    /** @return {inclusi, mancanti} */
    private static int[] giustificativi(Impaginatore p, Rapporto r, FonteAllegati fonte) {
        int inclusi = 0;
        int mancanti = 0;
        if (r.conGiustificativo.isEmpty()) {
            return new int[] {0, 0};
        }
        for (Spesa s : r.conGiustificativo) {
            p.nuovaPagina();
            if (s == r.conGiustificativo.get(0)) {
                p.sezione("Giustificativi");
            }
            int n = r.numeroAllegato(s);
            p.paragrafo("Allegato " + n + " · " + nz(s.descrizione), p.grande);
            p.paragrafo(Date.mostra(s.data) + (nz(s.categoria).isEmpty() ? "" : " · " + s.categoria) + " · "
                    + Money.format(s.importo), p.sottotitolo);
            p.spazio(8);
            Allegato a = fonte.trova(s);
            String problema = a.problema;
            if (problema == null) {
                try {
                    if (a.mime.equals("application/pdf") || a.file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        pagineDiPdf(p, a.file, n);
                    } else {
                        immagine(p, a.file);
                    }
                    inclusi++;
                    continue;
                } catch (IOException | RuntimeException e) {
                    problema = "il file non si può leggere";
                }
            }
            mancanti++;
            p.paragrafo("Giustificativo non incluso: " + problema + ".", p.testo);
            if (s.urlGiustificativo != null && !s.urlGiustificativo.isEmpty()) {
                p.spazio(4);
                p.paragrafo("Si trova su Google Drive: " + s.urlGiustificativo, p.piccolo);
            }
        }
        return new int[] {inclusi, mancanti};
    }

    private static void immagine(Impaginatore p, File file) throws IOException {
        BitmapFactory.Options misure = new BitmapFactory.Options();
        misure.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), misure);
        if (misure.outWidth <= 0 || misure.outHeight <= 0) {
            throw new IOException("Immagine non valida");
        }
        BitmapFactory.Options opzioni = new BitmapFactory.Options();
        opzioni.inSampleSize = 1;
        int lato = Math.max(misure.outWidth, misure.outHeight);
        // Si decodifica già ridotta (a potenze di 2) senza scendere sotto il lato voluto.
        while (lato / (opzioni.inSampleSize * 2) >= LATO_IMMAGINE) {
            opzioni.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opzioni);
        if (bitmap == null) {
            throw new IOException("Immagine non valida");
        }
        try {
            Bitmap ridotta = riduci(bitmap, LATO_IMMAGINE);
            p.immagine(ridotta);
            if (ridotta != bitmap) {
                ridotta.recycle();
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap riduci(Bitmap b, int latoMassimo) {
        int lato = Math.max(b.getWidth(), b.getHeight());
        if (lato <= latoMassimo) {
            return b;
        }
        float scala = (float) latoMassimo / lato;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scala), Math.round(b.getHeight() * scala), true);
    }

    /** Ogni pagina del PDF allegato diventa un'immagine; dalla seconda si va a pagina nuova. */
    private static void pagineDiPdf(Impaginatore p, File file, int numero) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            int totale = renderer.getPageCount();
            for (int i = 0; i < totale; i++) {
                if (i > 0) {
                    p.nuovaPagina();
                    p.paragrafo("Allegato " + numero + " · pagina " + (i + 1) + " di " + totale, p.sottotitolo);
                    p.spazio(8);
                }
                try (PdfRenderer.Page pagina = renderer.openPage(i)) {
                    int l = LARGHEZZA_PAGINA_ALLEGATO;
                    int a = Math.max(1, Math.round((float) l * pagina.getHeight() / pagina.getWidth()));
                    Bitmap bitmap = Bitmap.createBitmap(l, a, Bitmap.Config.ARGB_8888);
                    try {
                        bitmap.eraseColor(Color.WHITE);
                        pagina.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        p.immagine(bitmap);
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
        }
    }
}
