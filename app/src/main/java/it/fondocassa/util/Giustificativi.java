package it.fondocassa.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Acquisizione dei giustificativi: le immagini vengono ridotte e compresse, i PDF copiati così come sono. */
public final class Giustificativi {
    public static final int LATO_MASSIMO = 1600;
    private static final int QUALITA_JPEG = 80;

    /** File acquisito nella memoria privata dell'app, pronto per l'upload. */
    public static class Acquisito {
        public final File file;
        public final String mime;

        Acquisito(File file, String mime) {
            this.file = file;
            this.mime = mime;
        }
    }

    private Giustificativi() {
    }

    public static String autorita(Context context) {
        return context.getPackageName() + ".files";
    }

    /** File temporaneo (e relativo Uri) su cui la fotocamera scrive lo scatto. */
    public static File fileFotoTemporaneo(Context context) {
        File cartella = new File(context.getCacheDir(), "foto");
        //noinspection ResultOfMethodCallIgnored
        cartella.mkdirs();
        return new File(cartella, "scatto.jpg");
    }

    public static Uri uriPer(Context context, File file) {
        return FileProvider.getUriForFile(context, autorita(context), file);
    }

    /** Copia nella memoria dell'app il documento scelto, comprimendolo se è un'immagine. */
    public static Acquisito acquisisci(Context context, Uri origine) throws IOException {
        String mime = context.getContentResolver().getType(origine);
        if (mime == null) {
            // Alcuni provider non dichiarano il tipo: lo si riconosce dall'intestazione del file.
            mime = sembraPdf(context, origine) ? "application/pdf" : "image/jpeg";
        }
        File cartella = new File(context.getFilesDir(), "giustificativi");
        //noinspection ResultOfMethodCallIgnored
        cartella.mkdirs();
        String nome = UUID.randomUUID().toString();

        if (mime.startsWith("image/")) {
            File destinazione = new File(cartella, nome + ".jpg");
            comprimi(context, origine, destinazione);
            return new Acquisito(destinazione, "image/jpeg");
        }
        File destinazione = new File(cartella, nome + (mime.equals("application/pdf") ? ".pdf" : ".bin"));
        try (InputStream in = apri(context, origine); OutputStream out = new FileOutputStream(destinazione)) {
            byte[] blocco = new byte[8192];
            int letti;
            while ((letti = in.read(blocco)) != -1) {
                out.write(blocco, 0, letti);
            }
        }
        return new Acquisito(destinazione, mime);
    }

    private static boolean sembraPdf(Context context, Uri origine) throws IOException {
        byte[] inizio = new byte[4];
        try (InputStream in = apri(context, origine)) {
            return in.read(inizio) == 4
                    && inizio[0] == '%' && inizio[1] == 'P' && inizio[2] == 'D' && inizio[3] == 'F';
        }
    }

    /** Fattore di sottocampionamento (potenza di 2) per decodificare vicino al lato massimo. */
    public static int campionamento(int larghezza, int altezza, int latoMassimo) {
        int fattore = 1;
        while (Math.max(larghezza, altezza) / (fattore * 2) >= latoMassimo) {
            fattore *= 2;
        }
        return fattore;
    }

    private static void comprimi(Context context, Uri origine, File destinazione) throws IOException {
        BitmapFactory.Options limiti = new BitmapFactory.Options();
        limiti.inJustDecodeBounds = true;
        try (InputStream in = apri(context, origine)) {
            BitmapFactory.decodeStream(in, null, limiti);
        }
        BitmapFactory.Options opzioni = new BitmapFactory.Options();
        opzioni.inSampleSize = campionamento(limiti.outWidth, limiti.outHeight, LATO_MASSIMO);
        Bitmap bitmap;
        try (InputStream in = apri(context, origine)) {
            bitmap = BitmapFactory.decodeStream(in, null, opzioni);
        }
        if (bitmap == null) {
            throw new IOException("Immagine non leggibile");
        }
        int rotazione = 0;
        try (InputStream in = apri(context, origine)) {
            rotazione = new ExifInterface(in).getRotationDegrees();
        } catch (IOException ignored) {
            // senza EXIF l'immagine resta com'è
        }
        float scala = Math.min(1f, (float) LATO_MASSIMO / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scala < 1f || rotazione != 0) {
            Matrix matrice = new Matrix();
            matrice.postScale(scala, scala);
            matrice.postRotate(rotazione);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrice, true);
        }
        try (OutputStream out = new FileOutputStream(destinazione)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITA_JPEG, out)) {
                throw new IOException("Compressione non riuscita");
            }
        }
    }

    private static InputStream apri(Context context, Uri uri) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("File non accessibile");
        }
        return in;
    }
}
