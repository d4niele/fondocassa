/**
 * Fondocassa — API Gateway su Google Apps Script.
 *
 * Va pubblicato come Web App ("Esegui come: me", "Accesso: chiunque") da uno script
 * collegato al foglio Google privato. L'app Android lo chiama via HTTPS/JSON.
 *
 * Fogli (correlati tra loro tramite le chiavi):
 *
 *   Sync_Alunni   alunno_id ◄──────────┐ anagrafica (nome, attivo, note)
 *   Sync_Quote    id_quota             │ quote uguali per tutti (dovuto = somma degli importi)
 *   Sync_Entrate  id_transazione, alunno_id ─┘ + colonna "alunno" ricavata con CERCA.VERT
 *   Sync_Spese    id_spesa, url_giustificativo ──► file su Google Drive
 *   Vista_Classe  solo formule sugli altri fogli: bilancio, stato per alunno, registro uscite
 *
 * Azioni:
 *   GET  ?action=ping&token=…
 *   GET  ?action=export_all&token=…
 *   POST {token, action: "sync", alunni: [], quote: [], entrate: [], spese: []}
 *   POST {token, action: "upload_giustificativo", id_spesa, filename, mime_type, data_base64}
 *   POST {token, action: "backup", db_base64}  → ZIP su Drive con database e giustificativi
 *
 * Le risposte sono sempre JSON: {ok: true, …} oppure {ok: false, error: "…"}.
 */

var PROP_TOKEN = 'API_TOKEN';
var PROP_SPREADSHEET = 'SPREADSHEET_ID';
var PROP_CARTELLA = 'GIUSTIFICATIVI_FOLDER_ID';
var NOME_CARTELLA = 'Fondocassa_Giustificativi';
var PROP_CARTELLA_BACKUP = 'BACKUP_FOLDER_ID';
var NOME_CARTELLA_BACKUP = 'Fondocassa_Backup';
var FOGLIO_VISTA = 'Vista_Classe';
var MAX_BYTE_ALLEGATO = 20 * 1024 * 1024;

/**
 * Descrizione dei fogli di servizio: l'ordine delle colonne è quello del foglio.
 * tipo: 'testo' | 'data' | 'euro' (centesimi nel JSON, euro nel foglio) | 'bool'
 */
var TABELLE = {
  alunni: {
    foglio: 'Sync_Alunni',
    colonne: [
      { nome: 'alunno_id', tipo: 'testo' },
      { nome: 'nome', tipo: 'testo' },
      { nome: 'attivo', tipo: 'bool' },
      { nome: 'note', tipo: 'testo' },
      { nome: 'timestamp_sync', tipo: 'testo' }
    ],
    ordinaPer: 'nome'
  },
  quote: {
    foglio: 'Sync_Quote',
    colonne: [
      { nome: 'id_quota', tipo: 'testo' },
      { nome: 'descrizione', tipo: 'testo' },
      { nome: 'importo', json: 'importo_cent', tipo: 'euro' },
      { nome: 'data', tipo: 'data' },
      { nome: 'timestamp_sync', tipo: 'testo' }
    ]
  },
  entrate: {
    foglio: 'Sync_Entrate',
    colonne: [
      { nome: 'id_transazione', tipo: 'testo' },
      { nome: 'alunno_id', tipo: 'testo' },
      { nome: 'data', tipo: 'data' },
      { nome: 'importo', json: 'importo_cent', tipo: 'euro' },
      { nome: 'metodo', tipo: 'testo' },
      { nome: 'note', tipo: 'testo' },
      { nome: 'timestamp_sync', tipo: 'testo' }
    ],
    // Colonna di sola lettura, fuori dalla tabella 1:1: il nome dell'alunno dalla sua chiave.
    collegata: {
      titolo: 'alunno',
      formula: '=ARRAYFORMULA(IF(B2:B="","",IFERROR(VLOOKUP(B2:B,Sync_Alunni!A:B,2,FALSE),"?")))'
    }
  },
  spese: {
    foglio: 'Sync_Spese',
    colonne: [
      { nome: 'id_spesa', tipo: 'testo' },
      { nome: 'descrizione', tipo: 'testo' },
      { nome: 'categoria', tipo: 'testo' },
      { nome: 'data', tipo: 'data' },
      { nome: 'importo', json: 'importo_cent', tipo: 'euro' },
      { nome: 'url_giustificativo', tipo: 'testo' },
      { nome: 'timestamp_sync', tipo: 'testo' }
    ]
  }
};
var ORDINE_TABELLE = ['alunni', 'quote', 'entrate', 'spese'];

// ---------------------------------------------------------------------------
// Punti di ingresso della Web App
// ---------------------------------------------------------------------------

function doGet(e) {
  return rispondi_(function () {
    var p = (e && e.parameter) || {};
    verificaToken_(p.token);
    if (p.action === 'ping') {
      return {};
    }
    if (p.action === 'export_all') {
      return exportAll_();
    }
    throw new Error('Azione non riconosciuta: ' + p.action);
  });
}

function doPost(e) {
  return rispondi_(function () {
    var corpo;
    try {
      corpo = JSON.parse(e.postData.contents);
    } catch (errore) {
      throw new Error('Richiesta non valida: JSON atteso');
    }
    verificaToken_(corpo.token);
    var azioni = { sync: sync_, upload_giustificativo: uploadGiustificativo_, backup: backup_ };
    if (!Object.prototype.hasOwnProperty.call(azioni, corpo.action)) {
      throw new Error('Azione non riconosciuta: ' + corpo.action);
    }
    // Una scrittura alla volta: due sincronizzazioni contemporanee non si sovrascrivono.
    var lock = LockService.getScriptLock();
    lock.waitLock(30000);
    try {
      return azioni[corpo.action](corpo);
    } finally {
      lock.releaseLock();
    }
  });
}

function rispondi_(azione) {
  var risposta;
  try {
    risposta = azione();
    risposta.ok = true;
  } catch (errore) {
    risposta = { ok: false, error: String((errore && errore.message) || errore) };
  }
  return ContentService.createTextOutput(JSON.stringify(risposta))
    .setMimeType(ContentService.MimeType.JSON);
}

/** Prima istruzione di ogni richiesta: senza il token corretto non si legge né si scrive nulla. */
function verificaToken_(token) {
  var atteso = PropertiesService.getScriptProperties().getProperty(PROP_TOKEN);
  if (!atteso) {
    throw new Error('Token non configurato: esegui la funzione setup() nello script');
  }
  if (typeof token !== 'string' || token !== atteso) {
    throw new Error('Token non valido');
  }
}

// ---------------------------------------------------------------------------
// Azioni
// ---------------------------------------------------------------------------

/** Upsert dei record ricevuti; quelli con eliminato=true vengono rimossi dal foglio. */
function sync_(corpo) {
  var ss = cartellaDiLavoro_();
  var timestamp = new Date().toISOString();
  var risposta = { timestamp_sync: timestamp };
  ORDINE_TABELLE.forEach(function (chiave) {
    var record = corpo[chiave] || [];
    risposta[chiave] = record.length ? upsert_(ss, TABELLE[chiave], record, timestamp) : [];
  });
  aggiornaVista_(ss, timestamp);
  return risposta;
}

/** Legge integralmente i fogli di servizio per ricostruire il database dell'app. */
function exportAll_() {
  var ss = cartellaDiLavoro_();
  var risposta = {};
  ORDINE_TABELLE.forEach(function (chiave) {
    var tabella = TABELLE[chiave];
    var foglio = foglio_(ss, tabella);
    risposta[chiave] = leggiRighe_(ss, foglio, tabella).map(function (riga) {
      return rigaInRecord_(tabella, riga);
    });
  });
  return risposta;
}

/** Salva il file su Drive (sola lettura tramite link) e ne restituisce l'URL. */
function uploadGiustificativo_(corpo) {
  if (!corpo.id_spesa || !corpo.data_base64) {
    throw new Error('Upload non valido: servono id_spesa e data_base64');
  }
  var byte = Utilities.base64Decode(corpo.data_base64);
  if (byte.length > MAX_BYTE_ALLEGATO) {
    throw new Error('Allegato troppo grande');
  }
  var nome = String(corpo.id_spesa) + '_' + nomeSicuro_(corpo.filename || 'giustificativo');
  var blob = Utilities.newBlob(byte, corpo.mime_type || 'application/octet-stream', nome);
  var file;
  try {
    file = cartellaGiustificativi_().createFile(blob);
  } catch (errore) {
    // I dati passano dal foglio, i file da Drive: sono autorizzazioni distinte.
    throw new Error('Drive ha rifiutato il salvataggio del file. Apri l\'editor dello script, esegui la '
      + 'funzione setup e accetta i permessi richiesti per Google Drive. Dettaglio: '
      + String((errore && errore.message) || errore));
  }
  return { url: file.getUrl(), condivisione: condividi_(file) };
}

/**
 * Rende il file leggibile a chi ha il link. Gli account Google Workspace (scuola, azienda) possono
 * vietare la condivisione pubblica: in quel caso si ripiega sul dominio e, se nemmeno quello è
 * consentito, il file resta privato. L'upload non deve fallire per questo.
 */
function condividi_(file) {
  var tentativi = [
    ['link', DriveApp.Access.ANYONE_WITH_LINK],
    ['dominio', DriveApp.Access.DOMAIN_WITH_LINK]
  ];
  for (var i = 0; i < tentativi.length; i++) {
    try {
      file.setSharing(tentativi[i][1], DriveApp.Permission.VIEW);
      return tentativi[i][0];
    } catch (errore) {
      // criterio di condivisione non consentito: si prova il successivo
    }
  }
  return 'privato';
}

/**
 * Backup completo: crea su Drive (cartella privata Fondocassa_Backup) uno ZIP con il database
 * SQLite inviato dall'app e tutti i giustificativi già presenti nella cartella dedicata.
 */
function backup_(corpo) {
  if (!corpo.db_base64) {
    throw new Error('Backup non valido: manca il database');
  }
  var blob = [Utilities.newBlob(Utilities.base64Decode(corpo.db_base64), 'application/x-sqlite3', 'fondocassa.db')];
  var file = cartellaGiustificativi_().getFiles();
  while (file.hasNext()) {
    var giustificativo = file.next();
    blob.push(giustificativo.getBlob().setName('giustificativi/' + giustificativo.getName()));
  }
  var nome = 'fondocassa-backup-'
    + Utilities.formatDate(new Date(), cartellaDiLavoro_().getSpreadsheetTimeZone(), 'yyyyMMdd-HHmmss') + '.zip';
  var zip = cartella_(PROP_CARTELLA_BACKUP, NOME_CARTELLA_BACKUP).createFile(Utilities.zip(blob, nome));
  return { url: zip.getUrl(), nome: nome, giustificativi: blob.length - 1 };
}

// ---------------------------------------------------------------------------
// Tabelle
// ---------------------------------------------------------------------------

function upsert_(ss, tabella, record, timestamp) {
  var foglio = foglio_(ss, tabella);
  var righe = leggiRighe_(ss, foglio, tabella);
  var quante = righe.length;
  var indice = {};
  righe.forEach(function (riga, i) {
    indice[String(riga[0])] = i;
  });

  var chiaveJson = nomeJson_(tabella.colonne[0]);
  var confermati = [];
  record.forEach(function (r) {
    var id = r[chiaveJson];
    if (id === undefined || id === null || String(id) === '') {
      return; // record senza chiave: non confermato, l'app lo riproporrà
    }
    id = String(id);
    if (r.eliminato) {
      if (id in indice) {
        righe[indice[id]] = null;
        delete indice[id];
      }
    } else {
      var riga = recordInRiga_(tabella, r, timestamp);
      if (id in indice) {
        righe[indice[id]] = riga;
      } else {
        indice[id] = righe.length;
        righe.push(riga);
      }
    }
    confermati.push(id);
  });

  righe = righe.filter(function (riga) {
    return riga !== null;
  });
  if (tabella.ordinaPer) {
    var colonna = posizione_(tabella, tabella.ordinaPer);
    righe.sort(function (a, b) {
      return String(a[colonna]).toLowerCase().localeCompare(String(b[colonna]).toLowerCase());
    });
  }
  scriviRighe_(foglio, tabella, righe, quante);
  return confermati;
}

function recordInRiga_(tabella, record, timestamp) {
  return tabella.colonne.map(function (colonna) {
    if (colonna.nome === 'timestamp_sync') {
      return timestamp;
    }
    var valore = record[nomeJson_(colonna)];
    switch (colonna.tipo) {
      case 'euro':
        return Math.round(Number(valore) || 0) / 100;
      case 'bool':
        return valore !== false;
      default:
        return valore === undefined || valore === null ? '' : String(valore);
    }
  });
}

function rigaInRecord_(tabella, riga) {
  var record = {};
  tabella.colonne.forEach(function (colonna, i) {
    var valore = riga[i];
    switch (colonna.tipo) {
      case 'euro':
        valore = Math.round((Number(valore) || 0) * 100);
        break;
      case 'bool':
        valore = !(valore === false || String(valore).toUpperCase() === 'FALSE' || valore === '');
        break;
      default:
        valore = valore === undefined || valore === null ? '' : String(valore);
    }
    record[nomeJson_(colonna)] = valore;
  });
  return record;
}

function nomeJson_(colonna) {
  return colonna.json || colonna.nome;
}

function posizione_(tabella, nome) {
  for (var i = 0; i < tabella.colonne.length; i++) {
    if (tabella.colonne[i].nome === nome) {
      return i;
    }
  }
  throw new Error('Colonna sconosciuta: ' + nome);
}

/** Righe con chiave valorizzata; le date che Sheets ha convertito tornano testo ISO. */
function leggiRighe_(ss, foglio, tabella) {
  var ultima = foglio.getLastRow();
  if (ultima < 2) {
    return [];
  }
  var fuso = ss.getSpreadsheetTimeZone();
  var valori = foglio.getRange(2, 1, ultima - 1, tabella.colonne.length).getValues();
  return valori
    .filter(function (riga) {
      return String(riga[0]) !== '';
    })
    .map(function (riga) {
      return riga.map(function (cella, i) {
        if (cella instanceof Date) {
          return tabella.colonne[i].tipo === 'data'
            ? Utilities.formatDate(cella, fuso, 'yyyy-MM-dd')
            : cella.toISOString();
        }
        return cella;
      });
    });
}

function scriviRighe_(foglio, tabella, righe, quantePrima) {
  var larghezza = tabella.colonne.length;
  if (righe.length) {
    foglio.getRange(2, 1, righe.length, larghezza).setValues(righe);
  }
  if (quantePrima > righe.length) {
    foglio.getRange(2 + righe.length, 1, quantePrima - righe.length, larghezza).clearContent();
  }
}

/** Restituisce il foglio della tabella, creandolo con intestazioni e formati se manca. */
function foglio_(ss, tabella) {
  var foglio = ss.getSheetByName(tabella.foglio);
  if (foglio) {
    return foglio;
  }
  foglio = ss.insertSheet(tabella.foglio);
  var titoli = tabella.colonne.map(function (colonna) {
    return colonna.nome;
  });
  foglio.getRange(1, 1, 1, titoli.length).setValues([titoli]).setFontWeight('bold');
  foglio.setFrozenRows(1);
  tabella.colonne.forEach(function (colonna, i) {
    // Testo semplice per chiavi, date e timestamp: Sheets non deve reinterpretarli.
    if (colonna.tipo === 'testo' || colonna.tipo === 'data') {
      foglio.getRange(2, i + 1, foglio.getMaxRows() - 1, 1).setNumberFormat('@');
    } else if (colonna.tipo === 'euro') {
      foglio.getRange(2, i + 1, foglio.getMaxRows() - 1, 1).setNumberFormat('#,##0.00');
    }
  });
  if (tabella.collegata) {
    var colonnaExtra = tabella.colonne.length + 1;
    foglio.getRange(1, colonnaExtra).setValue(tabella.collegata.titolo).setFontWeight('bold');
    foglio.getRange(2, colonnaExtra).setFormula(tabella.collegata.formula);
  }
  return foglio;
}

// ---------------------------------------------------------------------------
// Vista_Classe: solo formule, sempre allineata ai fogli di servizio
// ---------------------------------------------------------------------------

var VISTA_CELLA_AGGIORNAMENTO = 'B10';
var VISTA_RIGA_TABELLE = 13;

function aggiornaVista_(ss, timestamp) {
  var vista = ss.getSheetByName(FOGLIO_VISTA) || creaVista_(ss);
  vista.getRange(VISTA_CELLA_AGGIORNAMENTO).setValue(timestamp);
}

function creaVista_(ss) {
  // Le formule dipendono dai fogli di servizio: devono esistere prima.
  ORDINE_TABELLE.forEach(function (chiave) {
    foglio_(ss, TABELLE[chiave]);
  });
  var vista = ss.insertSheet(FOGLIO_VISTA, 0);
  var r = VISTA_RIGA_TABELLE;

  vista.getRange('A1').setValue('Fondocassa di classe').setFontWeight('bold');
  vista.getRange('A2:A10').setValues([
    ['Quota per alunno'],
    ['Alunni attivi'],
    ['Previsto (quote × alunni)'],
    ['Versato'],
    ['Da versare'],
    ['Spese'],
    ['In cassa (reale)'],
    ['Cassa virtuale (se tutti versano)'],
    ['Aggiornato al']
  ]);
  var formule = [
    ['B2', '=SUM(Sync_Quote!C2:C)'],
    ['B3', '=COUNTIFS(Sync_Alunni!A2:A,"<>",Sync_Alunni!C2:C,TRUE)'],
    ['B4', '=B2*B3'],
    ['B5', '=SUM(Sync_Entrate!D2:D)'],
    // Somma dei residui dei soli alunni attivi: chi ha versato di più non copre gli altri.
    ['B6', '=SUMPRODUCT((Sync_Alunni!A2:A<>"")*(Sync_Alunni!C2:C=TRUE)'
      + '*(B2-SUMIF(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D)>0)'
      + '*(B2-SUMIF(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D)))'],
    ['B7', '=SUM(Sync_Spese!E2:E)'],
    ['B8', '=B5-B7'],
    ['B9', '=B8+B6'],

    // Tabella alunni (A:D): una riga per ogni riga di Sync_Alunni, collegata per alunno_id.
    ['A' + r, '=ARRAYFORMULA(IF(Sync_Alunni!A2:A="","",Sync_Alunni!B2:B))'],
    ['B' + r, '=ARRAYFORMULA(IF(Sync_Alunni!A2:A="","",'
      + 'SUMIF(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D)))'],
    ['C' + r, '=ARRAYFORMULA(IF(Sync_Alunni!A2:A="","",IF(Sync_Alunni!C2:C=FALSE,0,'
      + 'IF($B$2-SUMIF(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D)>0,'
      + '$B$2-SUMIF(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D),0))))'],
    ['D' + r, '=ARRAYFORMULA(IF(Sync_Alunni!A2:A="","",IF(Sync_Alunni!C2:C=FALSE,"Non attivo",'
      + 'IF($B$2-SUMIF(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D)>0,'
      + '"In sospeso","In regola"))))'],

    // Registro uscite (F:J), dalla più recente.
    ['F' + r, '=IFERROR(QUERY(Sync_Spese!A2:F,'
      + '"select D, C, B, E, F where A is not null order by D desc",0),"")']
  ];
  formule.forEach(function (f) {
    vista.getRange(f[0]).setFormula(f[1]);
  });

  vista.getRange(r - 1, 1, 1, 4)
    .setValues([['Alunno', 'Totale versato', 'Da versare', 'Stato']]).setFontWeight('bold');
  vista.getRange(r - 1, 6, 1, 5)
    .setValues([['Data', 'Categoria', 'Descrizione', 'Importo', 'Link giustificativo']])
    .setFontWeight('bold');
  vista.setFrozenRows(r - 1);
  return vista;
}

// ---------------------------------------------------------------------------
// Risorse Google
// ---------------------------------------------------------------------------

function cartellaDiLavoro_() {
  var id = PropertiesService.getScriptProperties().getProperty(PROP_SPREADSHEET);
  var ss = id ? SpreadsheetApp.openById(id) : SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    throw new Error('Foglio non trovato: collega lo script a un foglio o imposta ' + PROP_SPREADSHEET);
  }
  return ss;
}

function cartellaGiustificativi_() {
  return cartella_(PROP_CARTELLA, NOME_CARTELLA);
}

/** Cartella di Drive ricordata nelle proprietà dello script; viene creata al primo uso. */
function cartella_(proprietaId, nome) {
  var proprieta = PropertiesService.getScriptProperties();
  var id = proprieta.getProperty(proprietaId);
  if (id) {
    try {
      var esistente = DriveApp.getFolderById(id);
      if (esistente && !esistente.isTrashed()) {
        return esistente;
      }
    } catch (errore) {
      // cartella eliminata o non più accessibile: se ne crea una nuova qui sotto
    }
  }
  var cartella = DriveApp.createFolder(nome);
  proprieta.setProperty(proprietaId, cartella.getId());
  return cartella;
}

function nomeSicuro_(nome) {
  return String(nome).replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 80);
}

// ---------------------------------------------------------------------------
// Configurazione iniziale (da eseguire a mano dall'editor, una sola volta)
// ---------------------------------------------------------------------------

/**
 * Crea i fogli, la cartella dei giustificativi e il token segreto.
 * Il token viene scritto nel registro di esecuzione: va copiato nelle Impostazioni dell'app.
 */
function setup() {
  var proprieta = PropertiesService.getScriptProperties();
  var token = proprieta.getProperty(PROP_TOKEN);
  if (!token) {
    token = (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, '');
    proprieta.setProperty(PROP_TOKEN, token);
  }
  var ss = cartellaDiLavoro_();
  ORDINE_TABELLE.forEach(function (chiave) {
    foglio_(ss, TABELLE[chiave]);
  });
  if (!ss.getSheetByName(FOGLIO_VISTA)) {
    creaVista_(ss);
  }
  var cartella = cartellaGiustificativi_();
  Logger.log('Token segreto da inserire nell\'app: ' + token);
  Logger.log('Cartella giustificativi: ' + cartella.getUrl());
  return token;
}

/** Genera un nuovo token (quello vecchio smette subito di funzionare). */
function rigeneraToken() {
  PropertiesService.getScriptProperties().deleteProperty(PROP_TOKEN);
  return setup();
}
