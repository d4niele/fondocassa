# Fondocassa

[![CI](https://github.com/d4niele/fondocassa/actions/workflows/ci.yml/badge.svg)](https://github.com/d4niele/fondocassa/actions/workflows/ci.yml)

App Android nativa (Java) per gestire il fondocassa di una classe: quote degli alunni, spese con
giustificativi, e un foglio Google sempre aggiornato da condividere con i genitori.

- **Offline-first**: tutto viene salvato subito in SQLite sul telefono; la sincronizzazione parte da sola quando c'è rete o con il pulsante *Sincronizza*.
- **Google Sheets come specchio e backup**: un Google Apps Script fa da ponte, senza OAuth né chiavi nell'APK. Da un telefono nuovo si ricostruisce tutto con *Ripristina dati da Google Sheets*.
- **Giustificativi su Drive**: foto dello scontrino (compressa) o PDF, caricati in una cartella dedicata e collegati alla spesa.
- **Backup completo su Drive**: uno ZIP con il database SQLite e tutti i giustificativi, con un tocco.
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

### Importare l'elenco della classe

Quando la scheda *Alunni* è vuota compare il pulsante **Importa elenco da CSV o JSON**: si incolla
il testo oppure si sceglie un file. Le colonne sono `nome`, `cognome` e `note` (ad esempio i nomi
dei genitori); il separatore può essere `;`, `,` o tabulazione, e l'intestazione è facoltativa.

```csv
nome;cognome;note
Anna;Rossi;Genitori: Paola e Luca
Marco;Verdi;
```

```json
[{"nome": "Anna", "cognome": "Rossi", "note": "Genitori: Paola e Luca"},
 {"nome": "Marco", "cognome": "Verdi"}]
```

Gli alunni vengono salvati come «Cognome Nome», in ordine alfabetico come nel registro. Le note
restano nell'app e nel foglio privato: non compaiono in `Vista_Classe`.

Esempio: 20 alunni e una quota di avvio di 5 € → *Previsto* 100 €. Se hanno versato in 12 e sono
stati spesi 8 €: *Versato* 60 €, *Da versare* 40 €, *In cassa adesso* 52 €, *Cassa virtuale* 92 €.

## Installazione

### 1. Foglio Google e script

Conviene usare un account Gmail dedicato alla classe (es. `fondocassa.classe@gmail.com`), così il
passaggio di consegne negli anni successivi è semplice.

Si fa una volta sola, dal browser, in una decina di minuti.

1. **Crea il foglio**: vai su <https://sheets.new> e dagli un nome (es. «Fondocassa 3B»). Resterà **privato**: non condividerlo con nessuno.
2. **Incolla lo script**: *Estensioni → Apps Script*, cancella il codice presente e incolla il contenuto di [`apps-script/Code.gs`](apps-script/Code.gs). Salva.
3. **Esegui `setup`**: scegli la funzione **`setup`** nel menu accanto a *Esegui* e premi *Esegui*.
   - La prima volta Google chiede l'autorizzazione e mostra l'avviso «Google non ha verificato questa app»: è normale, lo script è tuo. *Avanzate → Vai a … (non sicuro) → Consenti*.
   - Lo script chiede accesso al foglio e a Drive: gli servono per scrivere i dati e salvare i giustificativi.
   - Vengono creati i fogli `Sync_*` e `Vista_Classe`, la cartella `Fondocassa_Giustificativi` su Drive e il token segreto.
4. **Copia il token**: nel *Registro esecuzioni*, in basso, compare la riga `Token segreto da inserire nell'app: …`. È la password con cui l'app parla con lo script.
5. **Pubblica la Web App**: *Esegui il deployment → Nuovo deployment*, tipo *Applicazione web* (dall'ingranaggio), con:
   - «Esegui come: **Me**», così lo script scrive nel tuo foglio con i tuoi permessi;
   - «Chi ha accesso: **Chiunque**», perché l'app non fa login Google.

   Copia l'**URL dell'applicazione web**, quello che termina con `/exec`.

«Chiunque» significa che l'URL è raggiungibile da tutti, ma senza token lo script rifiuta ogni
richiesta: tratta il token come una password.

| Situazione | Cosa fare |
|---|---|
| Hai perso il token | Riesegui `setup`: ristampa quello esistente senza cambiarlo. |
| Il token è finito in mani sbagliate | Esegui `rigeneraToken`: quello vecchio smette subito di funzionare e va aggiornato nell'app. |
| Aggiorni `Code.gs` | *Esegui il deployment → Gestisci deployment → matita → Versione: Nuova versione*. L'URL resta lo stesso; con un *Nuovo deployment* cambierebbe. |
| *Prova connessione* dà errore HTTP o «risposta non valida» | Controlla che l'accesso sia «Chiunque» e di aver copiato l'URL che termina con `/exec`, non quello di test (`/dev`). |

### 2. App

1. Scarica l'APK dall'ultima [release](https://github.com/d4niele/fondocassa/releases) e installalo (Android 8.0 o successivo).
2. Al primo avvio scegli un PIN.
3. *Impostazioni*: incolla URL della Web App e token, poi *Prova connessione*. Per portarli sul telefono, il modo più comodo è mandarteli in un messaggio a te stesso.
4. Su un telefono nuovo: *Impostazioni → Ripristina dati da Google Sheets*.
5. Se hai cancellato o modificato a mano delle righe nel foglio: *Impostazioni → Reinvia tutto al foglio* le riscrive a partire dai dati dell'app.

## Il foglio Google

I fogli sono correlati tra loro tramite le chiavi, come le tabelle del database dell'app:

```
Sync_Alunni   alunno_id ◄───────────┐   nome, attivo, note
Sync_Quote    id_quota              │   descrizione, importo (per alunno), data
Sync_Entrate  id_transazione, alunno_id ─┘  data, importo, metodo, note  (+ colonna «alunno» via CERCA.VERT)
Sync_Spese    id_spesa                  descrizione, categoria, data, importo, url_giustificativo ──► Drive
Vista_Classe  solo formule sui fogli sopra
```

- I fogli `Sync_*` sono le tabelle grezze, 1:1 con SQLite: **non vanno modificati a mano** (ogni riga ha anche `timestamp_sync`).
- Date (`gg/mm/aaaa`), importi in euro e data dell'ultima sincronizzazione sono formattati per la lettura, e le colonne hanno larghezze adatte al contenuto. I fogli creati da una versione precedente vengono riformattati da soli alla prima sincronizzazione dopo l'aggiornamento di `Code.gs`; da lì in poi le larghezze che cambi a mano restano.
- `Vista_Classe` contiene solo formule: bilancio (quota per alunno, previsto, versato, da versare, spese, cassa reale e virtuale), stato di ogni alunno e registro delle uscite con il link al giustificativo. Si aggiorna da sola a ogni sincronizzazione.

### Backup completo

*Impostazioni → Esporta backup su Google Drive* sincronizza i dati e crea, nella cartella privata
`Fondocassa_Backup`, un file `fondocassa-backup-AAAAMMGG-HHMMSS.zip` che contiene:

```
fondocassa.db            il database SQLite dell'app
giustificativi/…         tutti gli scontrini e le ricevute (jpg, png, pdf)
```

È un archivio da conservare (ad esempio a fine anno o al passaggio di consegne); il ripristino
dell'app avviene invece dai fogli `Sync_*` con *Ripristina dati da Google Sheets*.

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
- I giustificativi su Drive sono visibili in sola lettura a chi ha il link. Se l'account è Google Workspace (scuola, azienda) e vieta la condivisione pubblica, restano visibili solo nel dominio o, se nemmeno questo è permesso, solo a te.

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
| `POST {token, action:"backup", db_base64}` | `{ok, url, nome, giustificativi}` |

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
