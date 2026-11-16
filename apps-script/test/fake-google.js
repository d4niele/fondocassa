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

  clearContent() {
    for (let r = 0; r < this.numRows; r++) {
      for (let c = 0; c < this.numCols; c++) {
        this.sheet.scrivi(this.row + r, this.col + c, '');
      }
    }
    return this;
  }

  setFontWeight() {
    return this;
  }

  setNumberFormat(formato) {
    this.sheet.formati[this.col] = formato;
    return this;
  }
}

class FakeSheet {
  constructor(nome) {
    this.nome = nome;
    this.celle = new Map();
    this.formule = {};
    this.formati = {};
    this.righeBloccate = 0;
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

  getRange(a, col, numRows = 1, numCols = 1) {
    if (typeof a === 'string') {
      const m = /^([A-Z])(\d+)(?::([A-Z])(\d+))?$/.exec(a);
      if (!m) throw new Error('Notazione A1 non supportata dal finto foglio: ' + a);
      const c1 = m[1].charCodeAt(0) - 64;
      const r1 = Number(m[2]);
      const c2 = m[3] ? m[3].charCodeAt(0) - 64 : c1;
      const r2 = m[4] ? Number(m[4]) : r1;
      return new FakeRange(this, r1, c1, r2 - r1 + 1, c2 - c1 + 1);
    }
    return new FakeRange(this, a, col, numRows, numCols);
  }

  setFrozenRows(n) {
    this.righeBloccate = n;
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
  }

  getSheetByName(nome) {
    return this.fogli.find((f) => f.nome === nome) || null;
  }

  insertSheet(nome, indice) {
    const foglio = new FakeSheet(nome);
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
      createFile(blob) {
        const file = {
          ...blob,
          condivisione: null,
          setSharing(accesso, permesso) {
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
      formatDate: (data) => {
        // Sufficiente per i test: data locale a Roma in formato ISO.
        return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Rome' }).format(data);
      },
      getUuid: () => `00000000-0000-4000-8000-${String(++uuid).padStart(12, '0')}`,
    },
    DriveApp: {
      Access: { ANYONE_WITH_LINK: 'ANYONE_WITH_LINK' },
      Permission: { VIEW: 'VIEW' },
      createFolder: creaCartella,
      getFolderById: (id) => drive.cartelle.find((c) => c.getId() === id),
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
