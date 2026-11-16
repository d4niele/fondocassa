package it.fondocassa.ui;

import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputLayout;

import it.fondocassa.FondocassaApp;
import it.fondocassa.R;
import it.fondocassa.security.PinHasher;

/**
 * Schermata del PIN. Tre usi: creazione al primo avvio, sblocco, cambio PIN
 * (avviata con {@link #EXTRA_CAMBIO}: chiede il PIN attuale e poi quello nuovo due volte).
 */
public class LockActivity extends AppCompatActivity {
    public static final String EXTRA_CAMBIO = "cambio";

    private enum Fase { SBLOCCO, VERIFICA_ATTUALE, NUOVO, CONFERMA }

    private Fase fase;
    private String primoPin;
    private boolean cambio;

    private TextView messaggio;
    private EditText campo;
    private TextInputLayout layout;
    private Button conferma;

    private FondocassaApp app() {
        return (FondocassaApp) getApplication();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);
        BaseActivity.applicaMargini(this);
        cambio = getIntent().getBooleanExtra(EXTRA_CAMBIO, false);

        messaggio = findViewById(R.id.messaggio);
        campo = findViewById(R.id.campo_pin);
        layout = findViewById(R.id.layout_pin);
        conferma = findViewById(R.id.conferma);
        conferma.setOnClickListener(v -> avanti());
        campo.setOnEditorActionListener((v, azione, evento) -> {
            if (azione == EditorInfo.IME_ACTION_DONE) {
                avanti();
                return true;
            }
            return false;
        });

        boolean pinPresente = app().settings().pinHash() != null;
        vaiA(!pinPresente ? Fase.NUOVO : cambio ? Fase.VERIFICA_ATTUALE : Fase.SBLOCCO);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (cambio) {
                    finish();
                } else {
                    moveTaskToBack(true); // senza PIN non si entra
                }
            }
        });

        Button biometria = findViewById(R.id.biometria);
        if (fase == Fase.SBLOCCO && app().settings().biometria() && biometriaDisponibile(this)) {
            biometria.setVisibility(View.VISIBLE);
            biometria.setOnClickListener(v -> chiediBiometria());
            if (savedInstanceState == null) {
                chiediBiometria();
            }
        }
        campo.requestFocus();
    }

    public static boolean biometriaDisponibile(android.content.Context context) {
        return BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void vaiA(Fase nuova) {
        fase = nuova;
        campo.setText("");
        switch (nuova) {
            case SBLOCCO:
                messaggio.setText(R.string.pin_inserisci);
                conferma.setText(R.string.pin_sblocca);
                break;
            case VERIFICA_ATTUALE:
                messaggio.setText(R.string.pin_attuale);
                conferma.setText(R.string.pin_avanti);
                break;
            case NUOVO:
                messaggio.setText(R.string.pin_crea);
                conferma.setText(R.string.pin_avanti);
                break;
            default:
                messaggio.setText(R.string.pin_conferma);
                conferma.setText(R.string.azione_salva);
                break;
        }
    }

    private void avanti() {
        String pin = BaseActivity.testo(campo);
        layout.setError(null);
        switch (fase) {
            case SBLOCCO:
            case VERIFICA_ATTUALE:
                verifica(pin);
                break;
            case NUOVO:
                if (!PinHasher.valido(pin)) {
                    layout.setError(getString(R.string.pin_corto));
                    return;
                }
                primoPin = pin;
                vaiA(Fase.CONFERMA);
                break;
            default:
                if (!pin.equals(primoPin)) {
                    vaiA(Fase.NUOVO);
                    layout.setError(getString(R.string.pin_diverso));
                    return;
                }
                conferma.setEnabled(false);
                app().io().execute(() -> {
                    app().settings().setPinHash(PinHasher.hash(pin));
                    runOnUiThread(this::sbloccaEChiudi);
                });
                break;
        }
    }

    private void verifica(String pin) {
        conferma.setEnabled(false);
        String salvato = app().settings().pinHash();
        Fase faseVerificata = fase;
        app().io().execute(() -> {
            boolean corretto = PinHasher.verifica(pin, salvato);
            runOnUiThread(() -> {
                conferma.setEnabled(true);
                if (!corretto) {
                    campo.setText("");
                    layout.setError(getString(R.string.pin_errato));
                } else if (faseVerificata == Fase.VERIFICA_ATTUALE) {
                    vaiA(Fase.NUOVO);
                } else {
                    sbloccaEChiudi();
                }
            });
        });
    }

    private void chiediBiometria() {
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        sbloccaEChiudi();
                    }
                });
        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometria_titolo))
                .setNegativeButtonText(getString(R.string.pin_inserisci))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build());
    }

    private void sbloccaEChiudi() {
        app().sblocca();
        finish();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }
}
