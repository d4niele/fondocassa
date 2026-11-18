'use strict';
// Finti servizi Google (Sheets, Drive, Properties…) quanto basta per eseguire Code.gs in Node.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class FakeRange {
  constructor(sheet, row, col, numRows, numCols) {
    Object.assign(this, { sheet, row, col, numRows, numCols });
  }

  getValues() {
    const out = [];
    for (let r = 0; r < this.numRows; r++) {
      const riga = [];
      for (let c = 0; c < this.numCols; c++) {
        riga.push(this.sheet.cella(this.row + r, this.col + c));
      }
      out.push(riga);
    }
    return out;
  }

  setValues(valori) {
    if (valori.length !== this.numRows || valori.some((v) => v.length !== this.numCols)) {
      throw new Error('setValues: dimensioni diverse dal range');
    }
    valori.forEach((riga, r) =>
      riga.forEach((v, c) => this.sheet.scrivi(this.row + r, this.col + c, v)));
    return this;
  }

  setValue(valore) {
    this.sheet.scrivi(this.row, this.col, valore);
    return this;
  }

  setFormula(formula) {
    this.sheet.formule[`${this.row},${this.col}`] = formula;
    return this;
  }

  /** Calcola solo la formula di prova del separatore: in italiano "1,2" è un numero decimale. */
  getValue() {
    const formula = this.sheet.formule[`${this.row},${this.col}`];
    if (formula === '=SUM(1,2)') return this.sheet.ss.virgolaDecimale ? 1.2 : 3;
    return this.sheet.cella(this.row, this.col);
  }

  clearContent() {
    for (let r = 0; r < this.numRows; r++) {
      for (let c = 0; c < this.numCols; c++) {
        this.sheet.scrivi(this.row + r, this.col + c, '');
        delete this.sheet.formule[`${this.row + r},${this.col + c}`];
      }
    }
    return this;
  }

  /** Svuota valori, formule e formati, come Range.clear(). */
  clear() {
    this.clearContent();
    for (let r = 0; r < this.numRows; r++) {
      for (let c = 0; c < this.numCols; c++) {
        const k = `${this.row + r},${this.col + c}`;
        delete this.sheet.formatiCella[k];
        delete this.sheet.sfondi[k];
        delete this.sheet.stili[k];
      }
    }
    return this;
  }

  stile(nome, valore) {
    for (let r = 0; r < this.numRows; r++) {
      for (let c = 0; c < this.numCols; c++) {
        const k = `${this.row + r},${this.col + c}`;
        this.sheet.stili[k] = { ...this.sheet.stili[k], [nome]: valore };
      }
    }
    return this;
  }

  setFontWeight(v) {
    return this.stile('peso', v);
  }

  setFontSize(v) {
    return this.stile('corpo', v);
  }

  setFontColor(v) {
    return this.stile('colore', v);
  }

  setHorizontalAlignment(v) {
    return this.stile('allineamento', v);
  }

  setFontStyle(v) {
    return this.stile('stile', v);
  }

  /** Registra solo il bordo inferiore, l'unico usato dalle viste. */
  setBorder(sopra, sinistra, sotto, destra, verticale, orizzontale, colore, tipo) {
    return this.stile('bordoSotto', sotto ? `${tipo} ${colore}` : null);
  }

  setVerticalAlignment(v) {
    return this.stile('verticale', v);
  }

  merge() {
    this.sheet.unioni.push([this.row, this.col, this.numRows, this.numCols]);
    return this;
  }

  /** Scioglie le celle unite che iniziano dentro l'intervallo. */
  breakApart() {
    this.sheet.unioni = this.sheet.unioni.filter(([r, c]) => !(r >= this.row && r < this.row + this.numRows
      && c >= this.col && c < this.col + this.numCols));
    return this;
  }

  setWrap(v) {
    return this.stile('aCapo', v);
  }

  setWrapStrategy(v) {
    return this.stile('aCapo', v);
  }

  setDataValidation(regola) {
    for (let r = 0; r < this.numRows; r++) {
      for (let c = 0; c < this.numCols; c++) {
        this.sheet.convalide[`${this.row + r},${this.col + c}`] = regola;
      }
    }
    return this;
  }

  clearDataValidations() {
    for (let r = 0; r < this.numRows; r++) {
      for (let c = 0; c < this.numCols; c++) {
        delete this.sheet.convalide[`${this.row + r},${this.col + c}`];
      }
    }
    return this;
  }

  setBackground(colore) {
    this.sheet.sfondi[`${this.row},${this.col}`] = colore;
    return this;
  }

  /** Per colonna quando il range parte dalla riga 2 (fogli di servizio), e sempre per cella. */
  setNumberFormat(formato) {
    if (this.row === 2) this.sheet.formati[this.col] = formato;
    this.sheet.formatiCella[`${this.row},${this.col}`] = formato;
    return this;
  }
}

class FakeSheet {
  constructor(nome, ss) {
    this.nome = nome;
    this.ss = ss;
    this.celle = new Map();
    this.formule = {};
    this.formati = {};
    this.formatiCella = {};
    this.convalide = {};
    this.sfondi = {};
    this.larghezze = {};
    this.stili = {};
    this.unioni = [];
    this.righeBloccate = 0;
    this.grigliaNascosta = false;
    this.colonne = 26;
  }

  cella(r, c) {
    const v = this.celle.get(`${r},${c}`);
    return v === undefined ? '' : v;
  }

  scrivi(r, c, v) {
    if (v === '') {
      this.celle.delete(`${r},${c}`);
    } else {
      this.celle.set(`${r},${c}`, v);
    }
  }

  getName() {
    return this.nome;
  }

  getLastRow() {
    let ultima = 0;
    for (const chiave of this.celle.keys()) {
      ultima = Math.max(ultima, Number(chiave.split(',')[0]));
    }
    return ultima;
  }

  getMaxRows() {
    return 1000;
  }

  getMaxColumns() {
    return this.colonne;
  }

  getParent() {
    return this.ss;
  }

  /** Ultima colonna con un valore o una formula. */
  getLastColumn() {
    let ultima = 0;
    for (const chiave of [...this.celle.keys(), ...Object.keys(this.formule)]) {
      ultima = Math.max(ultima, Number(chiave.split(',')[1]));
    }
    return ultima;
  }

  deleteColumns(da, quante) {
    if (da + quante - 1 > this.colonne) throw new Error('deleteColumns: colonne inesistenti');
    if (this.getLastColumn() >= da) throw new Error('il finto foglio elimina solo colonne vuote in fondo');
    this.colonne -= quante;
    for (const k of Object.keys(this.larghezze)) if (Number(k) >= da) delete this.larghezze[k];
  }

  getRange(a, col, numRows = 1, numCols = 1) {
    if (typeof a === 'string') {
      const m = /^([A-Z])(\d+)(?::([A-Z])(\d+))?$/.exec(a);
      if (!m) throw new Error('Notazione A1 non supportata dal finto foglio: ' + a);
      const c1 = m[1].charCodeAt(0) - 64;
      const r1 = Number(m[2]);
      const c2 = m[3] ? m[3].charCodeAt(0) - 64 : c1;
      const r2 = m[4] ? Number(m[4]) : r1;
      return this.controlla(new FakeRange(this, r1, c1, r2 - r1 + 1, c2 - c1 + 1));
    }
    return this.controlla(new FakeRange(this, a, col, numRows, numCols));
  }

  /** Come Sheets: fuori dalle colonne esistenti l'intervallo non è valido. */
  controlla(range) {
    if (range.col + range.numCols - 1 > this.colonne) {
      throw new Error(`Le coordinate dell'intervallo non rientrano nelle dimensioni del foglio (${this.nome})`);
    }
    return range;
  }

  setFrozenRows(n) {
    this.righeBloccate = n;
  }

  setHiddenGridlines(v) {
    this.grigliaNascosta = v;
    return this;
  }

  setColumnWidth(colonna, larghezza) {
    this.larghezze[colonna] = larghezza;
    return this;
  }

  /** Per i test: righe di dati (dalla 2) per le prime n colonne. */
  dati(nColonne) {
    const ultima = this.getLastRow();
    return ultima < 2 ? [] : this.getRange(2, 1, ultima - 1, nColonne).getValues();
  }
}

class FakeSpreadsheet {
  constructor() {
    this.fogli = [];
    this.virgolaDecimale = false; // true = foglio in italiano: gli argomenti si separano con ";"
  }

  getSheetByName(nome) {
    return this.fogli.find((f) => f.nome === nome) || null;
  }

  insertSheet(nome, indice) {
    const foglio = new FakeSheet(nome, this);
    if (indice === undefined) this.fogli.push(foglio);
    else this.fogli.splice(indice, 0, foglio);
    return foglio;
  }

  getSpreadsheetTimeZone() {
    return 'Europe/Rome';
  }
}

/** Carica Code.gs in un contesto isolato con i finti servizi e ne restituisce le funzioni. */
function carica() {
  const ss = new FakeSpreadsheet();
  const proprieta = new Map();
  const drive = { cartelle: [], file: [] };
  const lock = { presi: 0, rilasciati: 0 };
  let uuid = 0;

  const creaCartella = (nome) => {
    const cartella = {
      nome,
      getId: () => 'cartella-' + nome,
      getUrl: () => 'https://drive.google.com/drive/folders/' + nome,
      cestinata: false,
      isTrashed() {
        return this.cestinata;
      },
      getFiles() {
        const miei = drive.file.filter((f) => f.cartella === nome);
        let i = 0;
        return { hasNext: () => i < miei.length, next: () => miei[i++] };
      },
      createFile(blob) {
        if (drive.erroreCreazione) {
          throw new Error(drive.erroreCreazione);
        }
        const file = {
          ...blob,
          cartella: nome,
          condivisione: null,
          getName: () => blob.nome,
          getBlob: () => ({ ...blob, setName: (n) => ({ ...blob, nome: n }) }),
          setSharing(accesso, permesso) {
            if ((drive.accessiVietati || []).includes(accesso)) {
              throw new Error('Accesso negato: DriveApp.');
            }
            this.condivisione = [accesso, permesso];
            return this;
          },
          getUrl: () => `https://drive.google.com/file/d/${drive.file.length}/view`,
        };
        drive.file.push(file);
        return file;
      },
    };
    drive.cartelle.push(cartella);
    return cartella;
  };

  const contesto = {
    console,
    JSON,
    Math,
    Date,
    SpreadsheetApp: {
      WrapStrategy: { CLIP: 'CLIP', WRAP: 'WRAP' },
      BorderStyle: { SOLID: 'SOLID' },
      flush: () => {},
      newDataValidation: () => ({
        requireCheckbox() {
          return { build: () => ({ tipo: 'casella di spunta' }) };
        },
      }),
      getActiveSpreadsheet: () => ss,
      openById: () => ss,
    },
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: (k) => (proprieta.has(k) ? proprieta.get(k) : null),
        setProperty: (k, v) => proprieta.set(k, v),
        deleteProperty: (k) => proprieta.delete(k),
      }),
    },
    LockService: {
      getScriptLock: () => ({
        waitLock: () => lock.presi++,
        releaseLock: () => lock.rilasciati++,
      }),
    },
    ContentService: {
      MimeType: { JSON: 'application/json' },
      createTextOutput: (testo) => ({
        testo,
        mime: null,
        setMimeType(m) {
          this.mime = m;
          return this;
        },
      }),
    },
    Utilities: {
      base64Decode: (s) => Array.from(Buffer.from(s, 'base64')),
      newBlob: (byte, mime, nome) => ({ byte, mime, nome }),
      zip: (voci, nome) => ({ nome, mime: 'application/zip', voci }),
      formatDate: (data, fuso, formato) => {
        // Sufficiente per i test: data (e ora) locale a Roma.
        const giorno = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Rome' }).format(data);
        if (formato === 'yyyy-MM-dd') return giorno;
        if (formato === 'dd/MM/yyyy HH:mm') {
          const [a, m, g] = giorno.split('-');
          const hm = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Rome', timeStyle: 'short' }).format(data);
          return `${g}/${m}/${a} ${hm}`;
        }
        const ora = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Rome', timeStyle: 'medium' }).format(data);
        return giorno.replace(/-/g, '') + '-' + ora.replace(/:/g, '');
      },
      parseDate: (testo, fuso, formato) => {
        if (formato !== 'yyyy-MM-dd' || fuso !== 'Europe/Rome') throw new Error('parseDate: caso non previsto');
        const [anno, mese, giorno] = testo.split('-').map(Number);
        // Mezzanotte a Roma: UTC+1 d'inverno, UTC+2 d'estate.
        for (const ore of [1, 2]) {
          const istante = new Date(Date.UTC(anno, mese - 1, giorno) - ore * 3600000);
          const locale = new Intl.DateTimeFormat('sv-SE', {
            timeZone: 'Europe/Rome', dateStyle: 'short', timeStyle: 'medium',
          }).format(istante);
          if (locale === `${testo} 00:00:00`) return istante;
        }
        throw new Error('parseDate: data non valida ' + testo);
      },
      getUuid: () => `00000000-0000-4000-8000-${String(++uuid).padStart(12, '0')}`,
    },
    DriveApp: {
      Access: { ANYONE_WITH_LINK: 'ANYONE_WITH_LINK', DOMAIN_WITH_LINK: 'DOMAIN_WITH_LINK' },
      Permission: { VIEW: 'VIEW' },
      createFolder: creaCartella,
      getFolderById: (id) => {
        const cartella = drive.cartelle.find((c) => c.getId() === id && !c.eliminata);
        if (!cartella) {
          throw new Error('No item with the given ID could be found, or you do not have permission to access it.');
        }
        return cartella;
      },
    },
    Logger: { righe: [], log(m) { this.righe.push(m); } },
  };
  vm.createContext(contesto);
  const sorgente = fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8');
  vm.runInContext(sorgente, contesto, { filename: 'Code.gs' });

  return {
    gs: contesto,
    ss,
    proprieta,
    drive,
    lock,
    get: (parametri) => JSON.parse(contesto.doGet({ parameter: parametri }).testo),
    post: (corpo) => JSON.parse(contesto.doPost({
      postData: { contents: typeof corpo === 'string' ? corpo : JSON.stringify(corpo) },
    }).testo),
  };
}

module.exports = { carica };
