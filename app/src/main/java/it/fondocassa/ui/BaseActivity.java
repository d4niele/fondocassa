package it.fondocassa.ui;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.time.LocalDate;

import it.fondocassa.FondocassaApp;
import it.fondocassa.util.Date;

/** Activity protetta dal PIN: se l'app è bloccata mostra prima la schermata di sblocco. */
public abstract class BaseActivity extends AppCompatActivity {

    protected FondocassaApp app() {
        return (FondocassaApp) getApplication();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (app().bloccata()) {
            startActivity(new Intent(this, LockActivity.class));
        }
    }

    /** Con il layout edge-to-edge tiene il contenuto fuori da barre di sistema e tastiera. */
    public static void applicaMargini(Activity activity) {
        View radice = activity.findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(radice, (v, insets) -> {
            Insets barre = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(barre.left, barre.top, barre.right, barre.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** Collega a un campo di testo non modificabile un selettore di data; il valore ISO sta nel tag. */
    public static void collegaData(EditText campo, String iso) {
        impostaData(campo, iso);
        campo.setOnClickListener(v -> {
            LocalDate attuale = Date.parse((String) campo.getTag());
            new DatePickerDialog(campo.getContext(),
                    (picker, anno, mese, giorno) -> impostaData(campo, Date.iso(anno, mese + 1, giorno)),
                    attuale.getYear(), attuale.getMonthValue() - 1, attuale.getDayOfMonth()).show();
        });
    }

    private static void impostaData(EditText campo, String iso) {
        campo.setTag(iso);
        campo.setText(Date.mostra(iso));
    }

    public static String testo(EditText campo) {
        return campo.getText() == null ? "" : campo.getText().toString().trim();
    }
}
