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
 * tipo: 'testo' | 'data' (ISO nel JSON, data vera nel foglio) | 'euro' (centesimi nel JSON,
 *       euro nel foglio) | 'bool' | 'istante' (data e ora dell'ultima sincronizzazione)
 * larghezza: in pixel; le chiavi tecniche restano strette, i testi da leggere larghi.
 */
var FORMATI = {
  testo: '@',
  data: 'dd/mm/yyyy',
  euro: '#,##0.00" €"',
  istante: 'dd/mm/yyyy hh:mm'
};
var COLORE_INTESTAZIONE = '#e8eef7';
var LARGHEZZA_COLLEGATA = 200;
// Cambiando formati o larghezze va aumentata: i fogli esistenti vengono riformattati una volta.
var PROP_FORMATO = 'FORMATO_FOGLI';
var VERSIONE_FORMATO = '2';
var TABELLE = {
  alunni: {
    foglio: 'Sync_Alunni',
    colonne: [
      { nome: 'alunno_id', tipo: 'testo', larghezza: 90 },
      { nome: 'nome', tipo: 'testo', larghezza: 220 },
      { nome: 'attivo', tipo: 'bool', larghezza: 70 },
      { nome: 'note', tipo: 'testo', larghezza: 260 },
      { nome: 'timestamp_sync', tipo: 'istante', larghezza: 150 }
    ],
    ordinaPer: 'nome'
  },
  quote: {
    foglio: 'Sync_Quote',
    colonne: [
      { nome: 'id_quota', tipo: 'testo', larghezza: 90 },
      { nome: 'descrizione', tipo: 'testo', larghezza: 280 },
      { nome: 'importo', json: 'importo_cent', tipo: 'euro', larghezza: 110 },
      { nome: 'data', tipo: 'data', larghezza: 100 },
      { nome: 'timestamp_sync', tipo: 'istante', larghezza: 150 }
    ]
  },
  entrate: {
    foglio: 'Sync_Entrate',
    colonne: [
      { nome: 'id_transazione', tipo: 'testo', larghezza: 90 },
      { nome: 'alunno_id', tipo: 'testo', larghezza: 90 },
      { nome: 'data', tipo: 'data', larghezza: 100 },
      { nome: 'importo', json: 'importo_cent', tipo: 'euro', larghezza: 110 },
      { nome: 'metodo', tipo: 'testo', larghezza: 120 },
      { nome: 'note', tipo: 'testo', larghezza: 260 },
      { nome: 'timestamp_sync', tipo: 'istante', larghezza: 150 }
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
      { nome: 'id_spesa', tipo: 'testo', larghezza: 90 },
      { nome: 'descrizione', tipo: 'testo', larghezza: 280 },
      { nome: 'categoria', tipo: 'testo', larghezza: 170 },
      { nome: 'data', tipo: 'data', larghezza: 100 },
      { nome: 'importo', json: 'importo_cent', tipo: 'euro', larghezza: 110 },
      { nome: 'url_giustificativo', tipo: 'testo', larghezza: 300 },
      { nome: 'timestamp_sync', tipo: 'istante', larghezza: 150 }
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
  try {
    aggiornaFormato_(ss);
  } catch (errore) {
    // La leggibilità del foglio non deve mai impedire di salvare i dati: si riprova alla prossima.
  }
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
  scriviRighe_(ss, foglio, tabella, righe, quante);
  return confermati;
}

function recordInRiga_(tabella, record, timestamp) {
  return tabella.colonne.map(function (colonna) {
    if (colonna.tipo === 'istante') {
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

/**
 * Scrive le righe: date e istanti, che nel JSON e in memoria sono testo ISO, diventano date vere
 * del foglio, così si leggono nel formato locale e si possono ordinare e filtrare.
 */
function scriviRighe_(ss, foglio, tabella, righe, quantePrima) {
  var larghezza = tabella.colonne.length;
  if (righe.length) {
    var fuso = ss.getSpreadsheetTimeZone();
    var valori = righe.map(function (riga) {
      return riga.map(function (cella, i) {
        var tipo = tabella.colonne[i].tipo;
        return tipo === 'data' ? inData_(cella, fuso) : tipo === 'istante' ? inIstante_(cella) : cella;
      });
    });
    foglio.getRange(2, 1, righe.length, larghezza).setValues(valori);
  }
  if (quantePrima > righe.length) {
    foglio.getRange(2 + righe.length, 1, quantePrima - righe.length, larghezza).clearContent();
  }
}

/** "2026-11-16" → mezzanotte di quel giorno nel fuso del foglio; ciò che non è una data ISO resta com'è. */
function inData_(valore, fuso) {
  if (typeof valore !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(valore)) {
    return valore;
  }
  try {
    return Utilities.parseDate(valore, fuso, 'yyyy-MM-dd');
  } catch (errore) {
    return valore;
  }
}

/** Timestamp ISO → data e ora del foglio; ciò che non lo è resta com'è. */
function inIstante_(valore) {
  if (typeof valore !== 'string' || !/^\d{4}-\d{2}-\d{2}T/.test(valore)) {
    return valore;
  }
  var istante = new Date(valore);
  return isNaN(istante.getTime()) ? valore : istante;
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
  foglio.getRange(1, 1, 1, titoli.length).setValues([titoli]);
  if (tabella.collegata) {
    var colonnaExtra = tabella.colonne.length + 1;
    foglio.getRange(1, colonnaExtra).setValue(tabella.collegata.titolo);
    foglio.getRange(2, colonnaExtra).setFormula(tabella.collegata.formula);
  }
  formattaFoglio_(foglio, tabella);
  return foglio;
}

/** Formati, larghezze e intestazione di un foglio di servizio. Si può rieseguire senza danni. */
function formattaFoglio_(foglio, tabella) {
  var quante = tabella.colonne.length + (tabella.collegata ? 1 : 0);
  var righe = foglio.getMaxRows() - 1;
  foglio.getRange(1, 1, 1, quante).setFontWeight('bold').setBackground(COLORE_INTESTAZIONE);
  foglio.setFrozenRows(1);
  tabella.colonne.forEach(function (colonna, i) {
    // Le chiavi e i testi restano testo semplice: Sheets non deve reinterpretarli.
    if (FORMATI[colonna.tipo] && righe > 0) {
      foglio.getRange(2, i + 1, righe, 1).setNumberFormat(FORMATI[colonna.tipo]);
    }
    foglio.setColumnWidth(i + 1, colonna.larghezza);
  });
  if (tabella.collegata) {
    foglio.setColumnWidth(quante, LARGHEZZA_COLLEGATA);
  }
}

/**
 * Porta i fogli creati da una versione precedente al formato attuale: una volta sola, perché
 * riformattare a ogni sincronizzazione la rallenterebbe. Le date salvate come testo vengono
 * riscritte come date vere.
 */
function aggiornaFormato_(ss) {
  var proprieta = PropertiesService.getScriptProperties();
  if (proprieta.getProperty(PROP_FORMATO) === VERSIONE_FORMATO) {
    return;
  }
  ORDINE_TABELLE.forEach(function (chiave) {
    var tabella = TABELLE[chiave];
    var foglio = ss.getSheetByName(tabella.foglio);
    if (!foglio) {
      return; // verrà creato già nel formato attuale
    }
    formattaFoglio_(foglio, tabella);
    var righe = leggiRighe_(ss, foglio, tabella);
    scriviRighe_(ss, foglio, tabella, righe, righe.length);
  });
  var vista = ss.getSheetByName(FOGLIO_VISTA);
  if (vista) {
    formattaVista_(vista);
  }
  proprieta.setProperty(PROP_FORMATO, VERSIONE_FORMATO);
}

// ---------------------------------------------------------------------------
// Vista_Classe: solo formule, sempre allineata ai fogli di servizio
// ---------------------------------------------------------------------------

var VISTA_CELLA_AGGIORNAMENTO = 'B10';
var VISTA_RIGA_TABELLE = 13;

function aggiornaVista_(ss, timestamp) {
  var vista = ss.getSheetByName(FOGLIO_VISTA) || creaVista_(ss);
  vista.getRange(VISTA_CELLA_AGGIORNAMENTO).setValue(inIstante_(timestamp));
}

var VISTA_LARGHEZZE = [260, 130, 120, 110, 30, 100, 170, 280, 110, 300];

/** Formati e larghezze di Vista_Classe: importi in euro, date leggibili. Si può rieseguire. */
function formattaVista_(vista) {
  var r = VISTA_RIGA_TABELLE;
  var righe = vista.getMaxRows() - r + 1;
  vista.getRange('A1').setFontWeight('bold');
  vista.getRange('B2:B9').setNumberFormat(FORMATI.euro);
  vista.getRange('B3').setNumberFormat('0');
  vista.getRange(VISTA_CELLA_AGGIORNAMENTO).setNumberFormat(FORMATI.istante);
  vista.getRange(r - 1, 1, 1, 4).setFontWeight('bold').setBackground(COLORE_INTESTAZIONE);
  vista.getRange(r - 1, 6, 1, 5).setFontWeight('bold').setBackground(COLORE_INTESTAZIONE);
  if (righe > 0) {
    vista.getRange(r, 2, righe, 2).setNumberFormat(FORMATI.euro);
    vista.getRange(r, 6, righe, 1).setNumberFormat(FORMATI.data);
    vista.getRange(r, 9, righe, 1).setNumberFormat(FORMATI.euro);
  }
  VISTA_LARGHEZZE.forEach(function (larghezza, i) {
    vista.setColumnWidth(i + 1, larghezza);
  });
  vista.setFrozenRows(r - 1);
}

function creaVista_(ss) {
  // Le formule dipendono dai fogli di servizio: devono esistere prima.
  ORDINE_TABELLE.forEach(function (chiave) {
    foglio_(ss, TABELLE[chiave]);
  });
  var vista = ss.insertSheet(FOGLIO_VISTA, 0);
  var r = VISTA_RIGA_TABELLE;

  vista.getRange('A1').setValue('Fondocassa di classe');
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

  vista.getRange(r - 1, 1, 1, 4).setValues([['Alunno', 'Totale versato', 'Da versare', 'Stato']]);
  vista.getRange(r - 1, 6, 1, 5)
    .setValues([['Data', 'Categoria', 'Descrizione', 'Importo', 'Link giustificativo']]);
  formattaVista_(vista);
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
  aggiornaFormato_(ss);
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
