/**
 * Fondo cassa — API Gateway su Google Apps Script.
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
 *   Sync_Fondi    id_fondo: fondo iniziale (avanzo degli anni passati)
 *   Sync_Impostazioni chiave, valore: impostazioni condivise (es. la classe «1ª C»)
 *   Vista_Classe  solo formule: il bilancio, leggibile da telefono
 *   Vista_Alunni  solo formule: versato e quanto manca per ogni alunno
 *   Vista_Spese   solo formule: registro delle uscite con il link alla ricevuta
 *
 * Azioni:
 *   GET  ?action=ping&token=…
 *   GET  ?action=export_all&token=…
 *   POST {token, action: "sync", alunni: [], quote: [], entrate: [], spese: [], fondi: [], impostazioni: []}
 *   POST {token, action: "upload_giustificativo", id_spesa, filename, mime_type, data_base64}
 *   POST {token, action: "backup", db_base64}  → ZIP su Drive con database e giustificativi
 *   POST {token, action: "scarica_giustificativo", id_spesa}  → {nome, mime_type, data_base64}
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
var VERSIONE_FORMATO = '16';
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
  },
  fondi: {
    foglio: 'Sync_Fondi',
    colonne: [
      { nome: 'id_fondo', tipo: 'testo', larghezza: 120 },
      { nome: 'descrizione', tipo: 'testo', larghezza: 280 },
      { nome: 'importo', json: 'importo_cent', tipo: 'euro', larghezza: 110 },
      { nome: 'data', tipo: 'data', larghezza: 100 },
      { nome: 'timestamp_sync', tipo: 'istante', larghezza: 150 }
    ]
  },
  impostazioni: {
    foglio: 'Sync_Impostazioni',
    colonne: [
      { nome: 'chiave', tipo: 'testo', larghezza: 120 },
      { nome: 'valore', tipo: 'testo', larghezza: 200 },
      { nome: 'timestamp_sync', tipo: 'istante', larghezza: 150 }
    ]
  }
};
var ORDINE_TABELLE = ['alunni', 'quote', 'entrate', 'spese', 'fondi', 'impostazioni'];

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
    var azioni = {
      sync: sync_,
      upload_giustificativo: uploadGiustificativo_,
      backup: backup_,
      scarica_giustificativo: scaricaGiustificativo_
    };
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
  var vistaSpese = ss.getSheetByName(FOGLIO_VISTA_SPESE);
  if (vistaSpese) {
    scriviVistaSpese_(ss, vistaSpese);
  }
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
 * Restituisce il giustificativo di una spesa, per metterlo nel PDF esportato dall'app (che dopo
 * l'upload non tiene più il file). Si cerca solo nella cartella dei giustificativi; se la spesa ha
 * cambiato allegato, vale il file più recente.
 */
function scaricaGiustificativo_(corpo) {
  if (!corpo.id_spesa) {
    throw new Error('Richiesta non valida: serve id_spesa');
  }
  var prefisso = String(corpo.id_spesa) + '_';
  var trovato = null;
  var file = cartellaGiustificativi_().getFiles();
  while (file.hasNext()) {
    var f = file.next();
    if (f.getName().indexOf(prefisso) !== 0 || f.isTrashed()) {
      continue;
    }
    if (!trovato || f.getLastUpdated() > trovato.getLastUpdated()) {
      trovato = f;
    }
  }
  if (!trovato) {
    throw new Error('Giustificativo non trovato su Drive');
  }
  var blob = trovato.getBlob();
  return {
    nome: trovato.getName(),
    mime_type: blob.getContentType(),
    data_base64: Utilities.base64Encode(blob.getBytes())
  };
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
  try {
    spunte_(foglio, tabella, righe.length, quantePrima);
  } catch (errore) {
    // Solo estetica: i valori VERO/FALSO sono già scritti.
  }
}

/**
 * Mostra le colonne vero/falso come caselle di spunta, solo sulle righe con dati: su quelle
 * vuote resterebbero caselle grigie senza significato.
 */
function spunte_(foglio, tabella, quante, quantePrima) {
  tabella.colonne.forEach(function (colonna, i) {
    if (colonna.tipo !== 'bool') {
      return;
    }
    if (quante) {
      foglio.getRange(2, i + 1, quante, 1)
        .setDataValidation(SpreadsheetApp.newDataValidation().requireCheckbox().build());
    }
    if (quantePrima > quante) {
      foglio.getRange(2 + quante, i + 1, quantePrima - quante, 1).clearDataValidations();
    }
  });
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
    scriviFormulaCollegata_(foglio, tabella);
  }
  formattaFoglio_(foglio, tabella);
  return foglio;
}

function scriviFormulaCollegata_(foglio, tabella) {
  foglio.getRange(2, tabella.colonne.length + 1)
    .setFormula(formula_(tabella.collegata.formula, separatore_(foglio)));
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
  estetica_(function () {
    togliColonneVuote_(foglio, quante);
  });
}

/**
 * Porta i fogli creati da una versione precedente al formato attuale: una volta sola, perché
 * riformattare a ogni sincronizzazione la rallenterebbe. Le date salvate come testo vengono
 * riscritte come date vere e le formule riscritte con il separatore giusto per la lingua del foglio.
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
    if (tabella.collegata) {
      scriviFormulaCollegata_(foglio, tabella);
    }
    var righe = leggiRighe_(ss, foglio, tabella);
    scriviRighe_(ss, foglio, tabella, righe, righe.length);
  });
  // Le formule delle viste possono riferirsi a fogli nuovi (es. Sync_Fondi): vanno creati prima.
  ORDINE_TABELLE.forEach(function (chiave) {
    foglio_(ss, TABELLE[chiave]);
  });
  // Le viste esistenti vengono ricomposte; se ce n'è almeno una, si creano anche quelle mancanti.
  if (VISTE.some(function (v) { return ss.getSheetByName(v.nome); })) {
    VISTE.forEach(function (v, posizione) {
      var vista = ss.getSheetByName(v.nome);
      if (vista) {
        v.componi(ss, vista);
      } else {
        creaVistaIn_(ss, v, posizione);
      }
    });
  }
  proprieta.setProperty(PROP_FORMATO, VERSIONE_FORMATO);
}

// ---------------------------------------------------------------------------
// Viste: solo formule sui fogli di servizio, pensate per lo schermo di un telefono
// ---------------------------------------------------------------------------

/*
 * Tre viste, ognuna in poche colonne strette (circa 360 px, un telefono in verticale), così che
 * si possa pubblicare sul web solo quella che serve:
 *
 *   Vista_Classe  il bilancio, senza dati dei singoli alunni
 *   Vista_Alunni  una riga per alunno: Alunno | Versato | Manca ("✓" se è in regola)
 *   Vista_Spese   Data | Descrizione | Importo | Ricevuta, dalla più recente
 *
 * In Vista_Classe ogni voce ha sotto, in piccolo, una riga che spiega cosa significa:
 *
 *    Fondo cassa di classe
 *    Aggiornato al 17/11/2026 19:23
 *
 *    In cassa adesso                 € 82,00   ← la cifra che interessa di più, in evidenza
 *    ↳ Fondo iniziale + versato − spese: i soldi che ci sono davvero
 *    ───────────────────────────────────────    ← una linea chiude ogni voce
 *    Cassa virtuale                 € 122,00
 *    ↳ Quanto avremmo se tutti versassero le quote
 *    …
 *
 * Nessuna riga bloccata: sul telefono ruberebbe metà dello schermo.
 */
var FOGLIO_VISTA_ALUNNI = 'Vista_Alunni';
var FOGLIO_VISTA_SPESE = 'Vista_Spese';
var VISTA_ALUNNI_RIGA = 4;
var VISTA_CELLA_AGGIORNAMENTO = 'A2';
var VISTA_LARGHEZZE = [170, 95, 95];
var VISTA_SPESE_LARGHEZZE = [75, 150, 80, 75];
var VISTA_CORPO_GRANDE = 14;
var VISTA_CORPO_SPIEGAZIONE = 9;
var VISTA_GRIGIO = '#5f6368';
var VISTA_LINEA = '#dadce0';
var VISTA_ETICHETTA_RICEVUTA = 'Ricevuta';
var VISTA_RIMANDO = '↳ ';

/** Versato da ogni alunno di Sync_Alunni, nello stesso ordine. */
var VERSATO_PER_ALUNNO = 'SUMIF(Sync_Entrate!B2:B,Sync_Alunni!A2:A,Sync_Entrate!D2:D)';

/**
 * Voci del bilancio, dall'alto in basso; null lascia una riga vuota tra un gruppo e l'altro.
 * La spiegazione, se c'è, va nella riga sotto il valore.
 * Nelle formule {id} diventa la cella del valore di un'altra voce.
 */
var VISTA_VOCI = [
  { id: 'cassa', etichetta: 'In cassa adesso', grande: true, formula: '={fondo}+{versato}-{spese}',
    spiegazione: 'Fondo iniziale + versato − spese: i soldi che ci sono davvero' },
  { id: 'virtuale', etichetta: 'Cassa virtuale', formula: '={cassa}+{daVersare}',
    spiegazione: 'Quanto avremmo se tutti versassero le quote (in cassa + da versare)' },
  null,
  { id: 'fondo', etichetta: 'Fondo iniziale', formula: '=SUM(Sync_Fondi!C2:C)',
    spiegazione: 'Avanzo degli anni passati, con cui la cassa è partita' },
  { id: 'versato', etichetta: 'Versato', formula: '=SUM(Sync_Entrate!D2:D)',
    spiegazione: 'Tutte le quote incassate dagli alunni' },
  { id: 'spese', etichetta: 'Spese', formula: '=SUM(Sync_Spese!E2:E)',
    spiegazione: 'Tutte le uscite: il dettaglio con le ricevute è nel foglio Vista_Spese' },
  // Somma dei residui dei soli alunni attivi: chi ha versato di più non copre gli altri.
  { id: 'daVersare', etichetta: 'Da versare',
    formula: '=SUMPRODUCT((Sync_Alunni!A2:A<>"")*(Sync_Alunni!C2:C=TRUE)'
      + '*({quota}-' + VERSATO_PER_ALUNNO + '>0)*({quota}-' + VERSATO_PER_ALUNNO + '))',
    spiegazione: 'Quanto manca: la somma di ciò che deve ancora ogni alunno' },
  null,
  { id: 'quota', etichetta: 'Quota a testa', formula: '=SUM(Sync_Quote!C2:C)',
    spiegazione: 'La somma delle quote richieste a ogni alunno' },
  { id: 'attivi', etichetta: 'Alunni attivi', formato: '0',
    formula: '=COUNTIFS(Sync_Alunni!A2:A,"<>",Sync_Alunni!C2:C,TRUE)' },
  { id: 'previsto', etichetta: 'Previsto', formula: '={quota}*{attivi}',
    spiegazione: 'Quota a testa × alunni attivi: quanto deve entrare in tutto' },
  // Calcolata qui e non contando i "✓" di Vista_Alunni: le viste non dipendono l'una dall'altra.
  { id: 'inRegola', etichetta: 'In regola', formato: 'testo',
    formula: '=SUMPRODUCT((Sync_Alunni!A2:A<>"")*(Sync_Alunni!C2:C=TRUE)'
      + '*({quota}-' + VERSATO_PER_ALUNNO + '<=0))&" su "&{attivi}',
    spiegazione: 'Alunni che hanno versato tutta la quota' }
];
var VISTA_TITOLO = '=IFERROR("Fondo cassa "&VLOOKUP("classe",Sync_Impostazioni!A2:B,2,FALSE),'
  + '"Fondo cassa di classe")';
var VISTA_SPIEGAZIONE_ALUNNI = 'Manca: quanto deve ancora versare l\'alunno. ✓ vuol dire in regola.';

/** Le viste nell'ordine delle schede, subito prima dei fogli di servizio. */
var VISTE = [
  { nome: FOGLIO_VISTA, componi: componiVista_ },
  { nome: FOGLIO_VISTA_ALUNNI, componi: componiVistaAlunni_ },
  { nome: FOGLIO_VISTA_SPESE, componi: componiVistaSpese_ }
];

/** Righe di Vista_Classe: per ogni voce quella del valore e quella della spiegazione. */
function righeVista_() {
  var righe = { voci: {}, spiegazioni: {} };
  var riga = 4;
  VISTA_VOCI.forEach(function (voce) {
    if (!voce) {
      riga++;
      return;
    }
    righe.voci[voce.id] = riga;
    riga++;
    if (voce.spiegazione) {
      righe.spiegazioni[voce.id] = riga;
      riga++;
    }
  });
  return righe;
}

function aggiornaVista_(ss, timestamp) {
  creaVisteMancanti_(ss);
  var vista = ss.getSheetByName(FOGLIO_VISTA);
  var quando = Utilities.formatDate(new Date(timestamp), ss.getSpreadsheetTimeZone(), 'dd/MM/yyyy HH:mm');
  vista.getRange(VISTA_CELLA_AGGIORNAMENTO).setValue('Aggiornato al ' + quando);
}

function creaVisteMancanti_(ss) {
  VISTE.forEach(function (v, posizione) {
    if (!ss.getSheetByName(v.nome)) {
      creaVistaIn_(ss, v, posizione);
    }
  });
}

function creaVistaIn_(ss, v, posizione) {
  // Le formule dipendono dai fogli di servizio: devono esistere prima.
  ORDINE_TABELLE.forEach(function (chiave) {
    foglio_(ss, TABELLE[chiave]);
  });
  var vista = ss.insertSheet(v.nome, posizione);
  v.componi(ss, vista);
  return vista;
}

/**
 * Scrive da zero Vista_Classe. Le colonne A:J sono della vista (nelle versioni precedenti c'erano
 * anche gli alunni e il registro delle uscite): vengono svuotate, quello che sta più a destra resta.
 */
function componiVista_(ss, vista) {
  var righe = righeVista_();
  svuotaVista_(vista, 10);

  var cella = function (id) {
    return 'B' + righe.voci[id];
  };
  // Il titolo riporta la classe impostata nell'app, se c'è: «Fondo cassa 1ª C».
  var formule = [['A1', VISTA_TITOLO]];
  VISTA_VOCI.forEach(function (voce) {
    if (!voce) {
      return;
    }
    vista.getRange(righe.voci[voce.id], 1).setValue(voce.etichetta);
    if (voce.spiegazione) {
      // La freccia e il rientro legano la spiegazione alla voce sopra, non a quella sotto.
      vista.getRange(righe.spiegazioni[voce.id], 1).setValue(VISTA_RIMANDO + voce.spiegazione);
    }
    var formula = voce.formula.replace(/\{(\w+)\}/g, function (tutto, id) {
        return cella(id);
      });
    formule.push([cella(voce.id), formula]);
  });
  scriviFormule_(ss, vista, formule);

  estetica_(function () {
    vista.getRange('A1').setFontWeight('bold').setFontSize(VISTA_CORPO_GRANDE);
    vista.getRange('A2').setFontColor(VISTA_GRIGIO);
    VISTA_VOCI.forEach(function (voce) {
      if (!voce) {
        return;
      }
      var valore = vista.getRange(cella(voce.id));
      if (voce.formato === 'testo') {
        valore.setHorizontalAlignment('right');
      } else {
        valore.setNumberFormat(voce.formato || FORMATI.euro);
      }
      if (voce.grande) {
        vista.getRange(righe.voci[voce.id], 1, 1, 2).setFontWeight('bold').setFontSize(VISTA_CORPO_GRANDE);
      }
      if (voce.spiegazione) {
        spiegazione_(vista, righe.spiegazioni[voce.id]);
      }
      // Una linea sottile chiude ogni voce (valore e spiegazione) e la separa dalla successiva.
      var ultima = voce.spiegazione ? righe.spiegazioni[voce.id] : righe.voci[voce.id];
      vista.getRange(ultima, 1, 1, VISTA_LARGHEZZE.length).setBorder(null, null, true, null, null, null,
        VISTA_LINEA, SpreadsheetApp.BorderStyle.SOLID);
    });
    stringiVista_(vista, VISTA_LARGHEZZE);
  });
}

/** Una riga per alunno, collegata per alunno_id. "Manca" è un importo, "✓" o "non attivo". */
function componiVistaAlunni_(ss, vista) {
  var r = VISTA_ALUNNI_RIGA;
  var quota = 'SUM(Sync_Quote!C2:C)';
  svuotaVista_(vista, 3);
  vista.getRange('A1').setValue('Situazione degli alunni');
  vista.getRange(2, 1).setValue(VISTA_SPIEGAZIONE_ALUNNI);
  vista.getRange(r - 1, 1, 1, 3).setValues([['Alunno', 'Versato', 'Manca']]);
  scriviFormule_(ss, vista, [
    ['A' + r, '=ARRAYFORMULA(IF(Sync_Alunni!A2:A="","",Sync_Alunni!B2:B))'],
    ['B' + r, '=ARRAYFORMULA(IF(Sync_Alunni!A2:A="","",' + VERSATO_PER_ALUNNO + '))'],
    ['C' + r, '=ARRAYFORMULA(IF(Sync_Alunni!A2:A="","",IF(Sync_Alunni!C2:C=FALSE,"non attivo",'
      + 'IF(' + quota + '-' + VERSATO_PER_ALUNNO + '>0,' + quota + '-' + VERSATO_PER_ALUNNO + ',"✓"))))']
  ]);
  estetica_(function () {
    vista.getRange('A1').setFontWeight('bold').setFontSize(VISTA_CORPO_GRANDE);
    spiegazione_(vista, 2);
    vista.getRange(r - 1, 1, 1, 3).setFontWeight('bold').setBackground(COLORE_INTESTAZIONE);
    vista.getRange(r - 1, 2, 1, 2).setHorizontalAlignment('right');
    var quante = vista.getMaxRows() - r + 1;
    vista.getRange(r, 2, quante, 1).setNumberFormat(FORMATI.euro);
    // Quanto manca in rosso; "✓" e "non attivo" sono testo e restano neri.
    vista.getRange(r, 3, quante, 1).setNumberFormat('[Red]#,##0.00" €"').setHorizontalAlignment('right');
    stringiVista_(vista, VISTA_LARGHEZZE);
  });
}

/** Svuota le prime colonne della vista, celle unite e righe bloccate comprese. */
function svuotaVista_(vista, colonne) {
  var tutta = vista.getRange(1, 1, vista.getMaxRows(), Math.min(colonne, vista.getMaxColumns()));
  tutta.breakApart();
  tutta.clear();
  vista.setFrozenRows(0);
}

/** Larghezze per il telefono, niente griglia e nessuna colonna vuota a destra. */
function stringiVista_(vista, larghezze) {
  larghezze.forEach(function (larghezza, i) {
    vista.setColumnWidth(i + 1, larghezza);
  });
  vista.setHiddenGridlines(true);
  togliColonneVuote_(vista, larghezze.length);
}

/** Riga di spiegazione: piccola, grigia, su tutta la larghezza della vista e a capo se serve. */
function spiegazione_(vista, riga) {
  vista.getRange(riga, 1, 1, VISTA_LARGHEZZE.length).merge()
    .setFontSize(VISTA_CORPO_SPIEGAZIONE)
    .setFontColor(VISTA_GRIGIO)
    .setFontStyle('italic')
    .setVerticalAlignment('top')
    .setWrap(true);
}

function componiVistaSpese_(ss, vista) {
  svuotaVista_(vista, 4);
  vista.getRange(1, 1, 1, 4).setValues([['Data', 'Descrizione', 'Importo', 'Ricevuta']]);
  estetica_(function () {
    var righe = vista.getMaxRows() - 1;
    vista.getRange(1, 1, 1, 4).setFontWeight('bold').setBackground(COLORE_INTESTAZIONE);
    vista.getRange(1, 1, righe + 1, 4).setHorizontalAlignment('center').setVerticalAlignment('middle');
    vista.setFrozenRows(1);
    vista.getRange(2, 1, righe, 1).setNumberFormat('dd/mm/yy');
    vista.getRange(2, 2, righe, 1).setWrap(true);
    vista.getRange(2, 3, righe, 1).setNumberFormat(FORMATI.euro);
    vista.getRange(2, 4, righe, 1).setWrapStrategy(SpreadsheetApp.WrapStrategy.CLIP);
    stringiVista_(vista, VISTA_SPESE_LARGHEZZE);
  });
  scriviVistaSpese_(ss, vista);
}

/**
 * Riscrive le uscite in Vista_Spese, dalla più recente. Sono valori e non una formula perché la
 * ricevuta deve essere un collegamento vero con la scritta «Ricevuta»: un HYPERLINK dentro una
 * formula sul telefono apre la formula invece del documento. Si aggiorna a ogni sincronizzazione.
 */
function scriviVistaSpese_(ss, vista) {
  var tabella = TABELLE.spese;
  var col = {
    id: posizione_(tabella, 'id_spesa'), descrizione: posizione_(tabella, 'descrizione'),
    data: posizione_(tabella, 'data'), importo: posizione_(tabella, 'importo'),
    url: posizione_(tabella, 'url_giustificativo')
  };
  var sorgente = ss.getSheetByName(tabella.foglio);
  var righe = sorgente ? leggiRighe_(ss, sorgente, tabella) : [];
  righe.sort(function (x, y) {
    var data = String(y[col.data]).localeCompare(String(x[col.data]));
    return data !== 0 ? data : String(x[col.id]).localeCompare(String(y[col.id]));
  });
  var vecchie = vista.getLastRow() - 1;
  if (vecchie > 0) {
    vista.getRange(2, 1, vecchie, 4).clearContent();
  }
  if (!righe.length) {
    return;
  }
  if (vista.getMaxRows() - 1 < righe.length) {
    vista.insertRowsAfter(vista.getMaxRows(), righe.length - vista.getMaxRows() + 1);
  }
  var fuso = ss.getSpreadsheetTimeZone();
  vista.getRange(2, 1, righe.length, 3).setValues(righe.map(function (r) {
    return [inData_(r[col.data], fuso), r[col.descrizione], r[col.importo]];
  }));
  vista.getRange(2, 4, righe.length, 1).setRichTextValues(righe.map(function (r) {
    var url = String(r[col.url] || '');
    var testo = SpreadsheetApp.newRichTextValue().setText(url ? VISTA_ETICHETTA_RICEVUTA : '');
    return [(url ? testo.setLinkUrl(url) : testo).build()];
  }));
}

function scriviFormule_(ss, vista, formule) {
  var separatore = separatore_(vista);
  formule.forEach(function (f) {
    vista.getRange(f[0]).setFormula(formula_(f[1], separatore));
  });
}

/**
 * Elimina le colonne vuote a destra di quelle usate: sul telefono non resta griglia inutile in cui
 * scorrere. Una colonna con qualcosa scritto a mano non viene mai eliminata.
 */
function togliColonneVuote_(foglio, usate) {
  var ultima = Math.max(usate, foglio.getLastColumn());
  var totali = foglio.getMaxColumns();
  if (totali > ultima) {
    foglio.deleteColumns(ultima + 1, totali - ultima);
  }
}

/** Formattazione: se una chiamata non riesce, i dati e le formule sono comunque già scritti. */
function estetica_(azione) {
  try {
    azione();
  } catch (errore) {
    Logger.log('Formattazione non applicata: ' + errore);
  }
}

/**
 * Separatore degli argomenti che il foglio si aspetta nelle formule. Nei fogli in italiano (e nelle
 * altre lingue con la virgola decimale) è il punto e virgola: con la virgola le formule danno
 * #ERROR!. Lo si scopre provando una formula in una cella libera, senza indovinare dalla lingua.
 */
function separatore_(foglio) {
  // La prova si fa nell'ultima riga della colonna "attivo" di Sync_Alunni: esiste sempre (le colonne
  // inutilizzate vengono eliminate), non ha un formato testo e non è mai coperta dai dati.
  var alunni = foglio_(foglio.getParent(), TABELLE.alunni);
  var prova = alunni.getRange(alunni.getMaxRows(), posizione_(TABELLE.alunni, 'attivo') + 1);
  try {
    prova.setFormula('=SUM(1,2)');
    SpreadsheetApp.flush();
    return prova.getValue() === 3 ? ',' : ';';
  } finally {
    prova.clearContent();
  }
}

/** Sostituisce le virgole tra gli argomenti, lasciando intatte quelle dentro le stringhe. */
function formula_(testo, separatore) {
  if (separatore === ',') {
    return testo;
  }
  var dentroStringa = false;
  return testo.split('').map(function (carattere) {
    if (carattere === '"') {
      dentroStringa = !dentroStringa;
    }
    return carattere === ',' && !dentroStringa ? separatore : carattere;
  }).join('');
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
  creaVisteMancanti_(ss);
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
