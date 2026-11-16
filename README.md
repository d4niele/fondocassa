# Fondocassa

[![CI](https://github.com/d4niele/fondocassa/actions/workflows/ci.yml/badge.svg)](https://github.com/d4niele/fondocassa/actions/workflows/ci.yml)

App Android nativa (Java) per gestire il fondocassa di una classe: quote degli alunni, spese con
giustificativi, e un foglio Google sempre aggiornato da condividere con i genitori.

- **Offline-first**: tutto viene salvato subito in SQLite sul telefono; la sincronizzazione parte da sola quando c'è rete o con il pulsante *Sincronizza*.
- **Google Sheets come specchio e backup**: un Google Apps Script fa da ponte, senza OAuth né chiavi nell'APK. Da un telefono nuovo si ricostruisce tutto con *Ripristina dati da Google Sheets*.
- **Giustificativi su Drive**: foto dello scontrino (compressa) o PDF, caricati in una cartella dedicata e collegati alla spesa.
- **Protetta da PIN** (e biometria, se attivata).

```
[ App Android · SQLite ] ──HTTPS/JSON──► [ Apps Script · Web App ] ──► Google Sheets
                                                                  └──► Google Drive (giustificativi)
```

## Come si usa

| Scheda | A cosa serve |
|---|---|
| **Riepilogo** | *In cassa adesso* (versato − spese), *Cassa virtuale* (quanto ci sarebbe se tutti avessero versato), e per le quote: *Previsto*, *Versato*, *Da versare*. |
| **Alunni** | L'elenco della classe con versato, residuo e stato (*In regola* / *In sospeso*). **Un tocco su un alunno apre l'incasso rapido**: importo già compilato col residuo, metodo (Contante / PayPal / Bonifico), data di oggi → *Registra quota*. |
| **Quote** | Le quote uguali per tutti, ad esempio «Quota di avvio — 5 €». Il dovuto di ogni alunno è la somma delle quote. |
| **Entrate** | Registro di tutti i versamenti, per correggerli o eliminarli. |
| **Spese** | Uscite con categoria e giustificativo (foto o PDF). |

Esempio: 20 alunni e una quota di avvio di 5 € → *Previsto* 100 €. Se hanno versato in 12 e sono
stati spesi 8 €: *Versato* 60 €, *Da versare* 40 €, *In cassa adesso* 52 €, *Cassa virtuale* 92 €.

## Installazione

### 1. Foglio Google e script

Conviene usare un account Gmail dedicato alla classe (es. `fondocassa.classe@gmail.com`), così il
passaggio di consegne negli anni successivi è semplice.

1. Crea un nuovo foglio Google (resterà **privato**).
2. *Estensioni → Apps Script*: incolla il contenuto di [`apps-script/Code.gs`](apps-script/Code.gs) al posto del codice presente.
3. Seleziona la funzione **`setup`** ed eseguila (la prima volta Google chiede l'autorizzazione). Crea i fogli e la cartella `Fondocassa_Giustificativi` su Drive.
4. Apri il *Registro esecuzioni*: copia il **token segreto** che vi compare.
5. *Esegui il deployment → Nuovo deployment → Applicazione web*: «Esegui come: **me**», «Chi ha accesso: **Chiunque**». Copia l'**URL della Web App** (termina con `/exec`).

Quando aggiorni `Code.gs`, pubblica una *nuova versione* dello stesso deployment: l'URL non cambia.

### 2. App

1. Scarica l'APK dall'ultima [release](https://github.com/d4niele/fondocassa/releases) e installalo (Android 8.0 o successivo).
2. Al primo avvio scegli un PIN.
3. *Impostazioni*: incolla URL della Web App e token, poi *Prova connessione*.
4. Su un telefono nuovo: *Impostazioni → Ripristina dati da Google Sheets*.

## Il foglio Google

I fogli sono correlati tra loro tramite le chiavi, come le tabelle del database dell'app:

```
Sync_Alunni   alunno_id ◄───────────┐   nome, attivo
Sync_Quote    id_quota              │   descrizione, importo (per alunno), data
Sync_Entrate  id_transazione, alunno_id ─┘  data, importo, metodo, note  (+ colonna «alunno» via CERCA.VERT)
Sync_Spese    id_spesa                  descrizione, categoria, data, importo, url_giustificativo ──► Drive
Vista_Classe  solo formule sui fogli sopra
```

- I fogli `Sync_*` sono le tabelle grezze, 1:1 con SQLite: **non vanno modificati a mano** (ogni riga ha anche `timestamp_sync`).
- `Vista_Classe` contiene solo formule: bilancio (quota per alunno, previsto, versato, da versare, spese, cassa reale e virtuale), stato di ogni alunno e registro delle uscite con il link al giustificativo. Si aggiorna da sola a ogni sincronizzazione.

### Condivisione con i genitori

Non condividere il foglio privato: anche in sola lettura, chi lo apre può vedere tutti i fogli.
Crea invece un **secondo foglio** e, nella cella `A1`, importa solo la vista:

```
=IMPORTRANGE("URL_DEL_FOGLIO_PRIVATO"; "Vista_Classe!A1:J")
```

Condividi quel secondo foglio come *Visualizzatore*. Se preferisci non mostrare gli importi dei
singoli, importa solo le colonne che vuoi (es. `Vista_Classe!A1:B10` per il solo bilancio).

### Sicurezza

- Ogni richiesta porta un **token segreto** verificato come prima istruzione dello script; senza token non si legge né si scrive nulla. Per cambiarlo esegui `rigeneraToken` nell'editor.
- Il traffico è solo HTTPS. Token e PIN (salvato come hash PBKDF2) stanno nella memoria privata dell'app, esclusa dai backup di Android.
- I giustificativi su Drive sono visibili in sola lettura a chi ha il link.

## Sviluppo

Requisiti: JDK 21 e Android SDK (piattaforma 36). Node 22 solo per i test dello script.

```bash
./gradlew testDebugUnitTest lintDebug   # test e lint dell'app
./gradlew assembleDebug                 # APK di debug in app/build/outputs/apk/debug/
node --test apps-script/test/*.test.js  # test dello script
```

| Percorso | Contenuto |
|---|---|
| `app/src/main/java/it/fondocassa/data` | Entità e DAO Room, `Repository`, impostazioni |
| `app/src/main/java/it/fondocassa/sync` | `ApiClient` (HTTP), `SyncManager` (esporta / ripristina), `SyncWorker` (background) |
| `app/src/main/java/it/fondocassa/ui` | Activity, schede e incasso rapido |
| `app/src/main/java/it/fondocassa/util` | Importi in centesimi, `Bilancio`, compressione dei giustificativi |
| `apps-script/` | Web App Google Apps Script e relativi test |

I test dell'app girano tutti sulla JVM (JUnit + Robolectric, senza emulatore): calcoli, database,
sincronizzazione contro un server HTTP finto, PIN e le schermate principali. I test dello script
usano finti servizi Google: verificano la logica e le formule scritte, non il loro calcolo in Sheets.

### Protocollo

| Richiesta | Risposta |
|---|---|
| `GET ?action=ping&token=…` | `{ok}` |
| `GET ?action=export_all&token=…` | `{ok, alunni[], quote[], entrate[], spese[]}` |
| `POST {token, action:"sync", alunni[], quote[], entrate[], spese[]}` | `{ok, timestamp_sync, alunni[id], quote[id], entrate[id], spese[id]}` |
| `POST {token, action:"upload_giustificativo", id_spesa, filename, mime_type, data_base64}` | `{ok, url}` |

Gli importi viaggiano in centesimi (`importo_cent`) e sono scritti in euro nel foglio. Un record con
`eliminato: true` viene rimosso dal foglio. In caso di errore: `{ok:false, error}`.

## Release

La CI ([`ci.yml`](.github/workflows/ci.yml)) esegue test, lint e build a ogni push e pull request.
Il workflow [`release.yml`](.github/workflows/release.yml) pubblica una release con l'APK firmato
quando viene caricato un tag di versione:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Serve, una sola volta, una chiave di firma. **Conservala con cura**: per aggiornare l'app già
installata, tutte le versioni devono essere firmate con la stessa chiave.

```bash
keytool -genkeypair -v -keystore fondocassa.jks -alias fondocassa \
  -keyalg RSA -keysize 4096 -validity 10000

gh secret set KEYSTORE_BASE64 < <(base64 -w0 fondocassa.jks)
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body fondocassa
gh secret set KEY_PASSWORD
```

Il file `.jks` non va mai aggiunto al repository (è già in `.gitignore`).
