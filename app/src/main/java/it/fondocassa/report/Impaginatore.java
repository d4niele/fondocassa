package it.fondocassa.report;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Scrive pagine su un {@link PdfDocument}: testo che va a capo, tabelle con l'intestazione
 * ripetuta a ogni pagina, immagini adattate allo spazio. Le misure sono in punti (1/72 di pollice).
 *
 * <p>Il PDF si legge soprattutto sul telefono (gira su WhatsApp): le pagine hanno la forma di uno
 * schermo (circa 12,7 × 22,6 cm) e il testo è grande abbastanza da non dover ingrandire.
 */
final class Impaginatore {
    static final float LARGHEZZA = 360;
    static final float ALTEZZA = 640;
    static final float MARGINE = 22;
    static final float UTILE = LARGHEZZA - 2 * MARGINE;
    private static final float INIZIO = 46;
    private static final float FINE = ALTEZZA - 38;

    static final int ACCENTO = 0xFF0F766E;
    static final int GRIGIO = 0xFF5F6368;
    private static final int LINEA = 0xFFDADCE0;
    private static final int FONDO_INTESTAZIONE = 0xFFE8EEF7;

    final Paint testo = paint(10.5f, false, Color.BLACK);
    final Paint grassetto = paint(10.5f, true, Color.BLACK);
    final Paint piccolo = paint(8.5f, false, GRIGIO);
    final Paint titolo = paint(22f, true, Color.BLACK);
    final Paint sottotitolo = paint(12f, false, GRIGIO);
    final Paint sezione = paint(15f, true, ACCENTO);
    final Paint grande = paint(16f, true, Color.BLACK);
    /** Intestazione e piè di pagina. */
    private final Paint margini = paint(7.5f, false, GRIGIO);
    private final Paint linea = new Paint();
    private final Paint fondo = new Paint();
    private final Paint immagine = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final Supporto supporto;
    private final String intestazione;
    private final String piede;
    private Canvas canvas;
    private int numero;
    private float y;

    /** Dove finiscono le pagine: un PDF vero o, nei test, pagine disegnate in memoria. */
    interface Supporto {
        Canvas nuovaPagina(int numero, int larghezza, int altezza);

        void chiudiPagina();

        void scrivi(OutputStream out) throws IOException;

        /** Libera le risorse; si può chiamare più volte. */
        void chiudi();
    }

    /** Il PDF di Android. */
    static final class SupportoPdf implements Supporto {
        private final PdfDocument documento = new PdfDocument();
        private PdfDocument.Page pagina;
        private boolean chiuso;

        @Override
        public Canvas nuovaPagina(int numero, int larghezza, int altezza) {
            pagina = documento.startPage(new PdfDocument.PageInfo.Builder(larghezza, altezza, numero).create());
            return pagina.getCanvas();
        }

        @Override
        public void chiudiPagina() {
            if (pagina != null) {
                documento.finishPage(pagina);
                pagina = null;
            }
        }

        @Override
        public void scrivi(OutputStream out) throws IOException {
            documento.writeTo(out);
        }

        @Override
        public void chiudi() {
            if (!chiuso) {
                chiuso = true;
                pagina = null; // una pagina ancora aperta si scarta insieme al documento
                documento.close();
            }
        }
    }

    /** Colonna di una tabella: titolo, larghezza (frazione dello spazio utile) e allineamento. */
    static final class Colonna {
        final String titolo;
        final float peso;
        final boolean destra;

        Colonna(String titolo, float peso, boolean destra) {
            this.titolo = titolo;
            this.peso = peso;
            this.destra = destra;
        }
    }

    /**
     * @param intestazione testo in piccolo in cima a ogni pagina (titolo e data del report)
     * @param piede        testo in piccolo in fondo a ogni pagina, accanto al numero di pagina
     */
    Impaginatore(Supporto supporto, String intestazione, String piede) {
        this.supporto = supporto;
        this.intestazione = intestazione;
        this.piede = piede;
        linea.setColor(LINEA);
        linea.setStrokeWidth(0.6f);
        fondo.setColor(FONDO_INTESTAZIONE);
        nuovaPagina();
    }

    private static Paint paint(float dimensione, boolean bold, int colore) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(dimensione);
        p.setColor(colore);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    int pagine() {
        return numero;
    }

    void nuovaPagina() {
        chiudiPagina();
        numero++;
        canvas = supporto.nuovaPagina(numero, (int) LARGHEZZA, (int) ALTEZZA);
        canvas.drawText(intestazione, MARGINE, 24, margini);
        canvas.drawLine(MARGINE, 30, LARGHEZZA - MARGINE, 30, linea);
        canvas.drawLine(MARGINE, ALTEZZA - 30, LARGHEZZA - MARGINE, ALTEZZA - 30, linea);
        canvas.drawText(piede, MARGINE, ALTEZZA - 18, margini);
        String n = "Pagina " + numero;
        canvas.drawText(n, LARGHEZZA - MARGINE - margini.measureText(n), ALTEZZA - 18, margini);
        y = INIZIO;
    }

    private void chiudiPagina() {
        if (canvas != null) {
            supporto.chiudiPagina();
            canvas = null;
        }
    }

    /** Spazio rimasto sulla pagina corrente. */
    float restante() {
        return FINE - y;
    }

    /** Se non c'è almeno {@code altezza} di spazio, passa alla pagina successiva. */
    void assicura(float altezza) {
        if (y + altezza > FINE) {
            nuovaPagina();
        }
    }

    void spazio(float altezza) {
        y += altezza;
    }

    /** Un paragrafo che va a capo da solo, alla larghezza della pagina. */
    void paragrafo(String s, Paint p) {
        for (String riga : righe(s, p, UTILE)) {
            float alta = interlinea(p);
            assicura(alta);
            y += alta;
            canvas.drawText(riga, MARGINE, y - discesa(p), p);
        }
    }

    /** Titolo di sezione, sempre con un po' di spazio sotto: se non ci sta, va alla pagina dopo. */
    void sezione(String s) {
        spazio(10);
        assicura(interlinea(sezione) + 60);
        y += interlinea(sezione);
        canvas.drawText(s, MARGINE, y - discesa(sezione), sezione);
        y += 4;
        canvas.drawLine(MARGINE, y, LARGHEZZA - MARGINE, y, linea);
        y += 6;
    }

    /** Una riga «etichetta ...... valore», con una spiegazione opzionale in piccolo sotto. */
    void voce(String etichetta, String valore, String spiegazione, boolean evidenza) {
        Paint p = evidenza ? grande : testo;
        Paint pv = evidenza ? grande : grassetto;
        List<String> sotto = spiegazione == null ? new ArrayList<>() : righe(spiegazione, piccolo, UTILE - 90);
        assicura(interlinea(p) + sotto.size() * interlinea(piccolo) + 6);
        y += interlinea(p);
        canvas.drawText(etichetta, MARGINE, y - discesa(p), p);
        canvas.drawText(valore, LARGHEZZA - MARGINE - pv.measureText(valore), y - discesa(p), pv);
        for (String riga : sotto) {
            y += interlinea(piccolo);
            canvas.drawText(riga, MARGINE, y - discesa(piccolo), piccolo);
        }
        y += 4;
        canvas.drawLine(MARGINE, y, LARGHEZZA - MARGINE, y, linea);
        y += 2;
    }

    /**
     * Tabella con intestazione ripetuta a ogni cambio pagina. Le celle vanno a capo; quello che in
     * una cella segue un "\n" è un dettaglio e va in piccolo sotto. L'ultima riga, se
     * {@code totale} non è null, è in grassetto.
     */
    void tabella(Colonna[] colonne, List<String[]> righe, String[] totale) {
        float[] larghezze = new float[colonne.length];
        float pesi = 0;
        for (Colonna c : colonne) {
            pesi += c.peso;
        }
        for (int i = 0; i < colonne.length; i++) {
            larghezze[i] = UTILE * colonne[i].peso / pesi;
        }
        intestazioneTabella(colonne, larghezze);
        for (String[] riga : righe) {
            rigaTabella(colonne, larghezze, riga, testo, true);
        }
        if (totale != null) {
            rigaTabella(colonne, larghezze, totale, grassetto, true);
        }
    }

    private static final float PADDING = 4;

    private void intestazioneTabella(Colonna[] colonne, float[] larghezze) {
        float alta = interlinea(grassetto) + 2 * PADDING;
        assicura(alta + interlinea(testo) + 2 * PADDING);
        canvas.drawRect(MARGINE, y, LARGHEZZA - MARGINE, y + alta, fondo);
        float x = MARGINE;
        for (int i = 0; i < colonne.length; i++) {
            scriviCella(colonne[i].titolo, x, larghezze[i], y + PADDING + interlinea(grassetto), grassetto,
                    colonne[i].destra);
            x += larghezze[i];
        }
        y += alta;
    }

    private void rigaTabella(Colonna[] colonne, float[] larghezze, String[] valori, Paint p, boolean bordo) {
        List<List<String>> testi = new ArrayList<>();
        List<List<Paint>> stili = new ArrayList<>();
        float altezzaCella = interlinea(p);
        for (int i = 0; i < colonne.length; i++) {
            String v = i < valori.length && valori[i] != null ? valori[i] : "";
            List<String> t = new ArrayList<>();
            List<Paint> s = new ArrayList<>();
            String[] parti = v.split("\n", 2);
            for (String r : righe(parti[0], p, larghezze[i] - 2 * PADDING)) {
                t.add(r);
                s.add(p);
            }
            if (parti.length > 1 && !parti[1].isEmpty()) {
                for (String r : righe(parti[1], piccolo, larghezze[i] - 2 * PADDING)) {
                    t.add(r);
                    s.add(piccolo);
                }
            }
            float alta = 0;
            for (Paint stile : s) {
                alta += interlinea(stile);
            }
            altezzaCella = Math.max(altezzaCella, alta);
            testi.add(t);
            stili.add(s);
        }
        float alta = altezzaCella + 2 * PADDING;
        if (y + alta > FINE) {
            nuovaPagina();
            intestazioneTabella(colonne, larghezze);
        }
        float x = MARGINE;
        for (int i = 0; i < colonne.length; i++) {
            float base = y + PADDING;
            for (int k = 0; k < testi.get(i).size(); k++) {
                Paint stile = stili.get(i).get(k);
                base += interlinea(stile);
                scriviCella(testi.get(i).get(k), x, larghezze[i], base, stile, colonne[i].destra);
            }
            x += larghezze[i];
        }
        y += alta;
        if (bordo) {
            canvas.drawLine(MARGINE, y, LARGHEZZA - MARGINE, y, linea);
        }
    }

    private void scriviCella(String s, float x, float larghezza, float base, Paint p, boolean destra) {
        float inizio = destra ? x + larghezza - PADDING - p.measureText(s) : x + PADDING;
        canvas.drawText(s, inizio, base - discesa(p), p);
    }

    /**
     * Disegna un'immagine adattandola allo spazio restante della pagina (senza ingrandirla oltre
     * la sua dimensione a 150 dpi) e centrandola.
     */
    void immagine(Bitmap bitmap) {
        float maxL = UTILE;
        float maxA = restante();
        float naturaleL = bitmap.getWidth() * 72f / 150f;
        float naturaleA = bitmap.getHeight() * 72f / 150f;
        float scala = Math.min(1f, Math.min(maxL / naturaleL, maxA / naturaleA));
        float l = naturaleL * scala;
        float a = naturaleA * scala;
        float x = MARGINE + (UTILE - l) / 2;
        canvas.drawBitmap(bitmap, null, new RectF(x, y, x + l, y + a), immagine);
        y += a;
    }

    void scrivi(OutputStream out) throws IOException {
        chiudiPagina();
        try {
            supporto.scrivi(out);
        } finally {
            supporto.chiudi();
        }
    }

    /** Annulla il documento senza scriverlo (in caso di errore). */
    void abbandona() {
        supporto.chiudi();
    }

    static float interlinea(Paint p) {
        return p.getTextSize() * 1.35f;
    }

    private static float discesa(Paint p) {
        return p.getTextSize() * 0.3f;
    }

    /** Quanti caratteri iniziali della parola stanno nella larghezza (almeno uno). */
    private static int quantiCiStanno(String parola, Paint p, float larghezza) {
        int basso = 1;
        int alto = parola.length();
        while (basso < alto) {
            int medio = (basso + alto + 1) / 2;
            if (p.measureText(parola, 0, medio) <= larghezza) {
                basso = medio;
            } else {
                alto = medio - 1;
            }
        }
        return basso;
    }

    /** Spezza il testo in righe che stanno nella larghezza data, andando a capo tra le parole. */
    static List<String> righe(String testo, Paint p, float larghezza) {
        List<String> risultato = new ArrayList<>();
        for (String paragrafo : testo.split("\n", -1)) {
            StringBuilder riga = new StringBuilder();
            for (String parola : paragrafo.split(" ")) {
                String prova = riga.length() == 0 ? parola : riga + " " + parola;
                if (p.measureText(prova) <= larghezza) {
                    riga.setLength(0);
                    riga.append(prova);
                    continue;
                }
                if (riga.length() > 0) {
                    risultato.add(riga.toString());
                    riga.setLength(0);
                }
                // Una parola più lunga della colonna (un link, un codice) viene spezzata.
                while (p.measureText(parola) > larghezza && parola.length() > 1) {
                    int quanti = quantiCiStanno(parola, p, larghezza);
                    risultato.add(parola.substring(0, quanti));
                    parola = parola.substring(quanti);
                }
                riga.append(parola);
            }
            risultato.add(riga.toString());
        }
        return risultato;
    }
}
