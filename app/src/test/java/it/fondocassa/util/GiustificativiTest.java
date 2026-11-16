package it.fondocassa.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class GiustificativiTest {
    @Test
    public void campionamentoRiduceSoloLeImmaginiGrandi() {
        assertEquals(1, Giustificativi.campionamento(800, 600, 1600));
        assertEquals(1, Giustificativi.campionamento(3000, 2000, 1600));
        assertEquals(2, Giustificativi.campionamento(4000, 3000, 1600));
        assertEquals(4, Giustificativi.campionamento(8000, 6000, 1600));
    }

    @Test
    public void leFotoVengonoRidimensionateECompresse() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File originale = new File(context.getCacheDir(), "grande.png");
        Bitmap grande = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(originale)) {
            grande.compress(Bitmap.CompressFormat.PNG, 100, out);
        }

        Giustificativi.Acquisito acquisito = Giustificativi.acquisisci(context, Uri.fromFile(originale));

        assertEquals("image/jpeg", acquisito.mime);
        assertTrue(acquisito.file.getName().endsWith(".jpg"));
        Bitmap ridotta = BitmapFactory.decodeFile(acquisito.file.getAbsolutePath());
        assertEquals(Giustificativi.LATO_MASSIMO, Math.max(ridotta.getWidth(), ridotta.getHeight()));
        assertEquals(1200, Math.min(ridotta.getWidth(), ridotta.getHeight()));
    }

    @Test
    public void iPdfVengonoCopiatiSenzaModifiche() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File pdf = new File(context.getCacheDir(), "ricevuta.pdf");
        byte[] contenuto = "%PDF-1.4 finto".getBytes();
        Files.write(pdf.toPath(), contenuto);

        Giustificativi.Acquisito acquisito = Giustificativi.acquisisci(context, Uri.fromFile(pdf));

        assertEquals("application/pdf", acquisito.mime);
        assertTrue(acquisito.file.getName().endsWith(".pdf"));
        assertEquals(new String(contenuto), new String(Files.readAllBytes(acquisito.file.toPath())));
    }
}
