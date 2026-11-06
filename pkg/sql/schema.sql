-- schema.sql - database lokal Mas Kiplay (SQLite).
-- Aman dijalankan berulang.

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS users (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    nama     TEXT    NOT NULL,
    usia     INTEGER CHECK (usia IS NULL OR (usia > 0 AND usia < 150)),
    bidang   TEXT,
    foto     TEXT,
    catatan  TEXT,
    dibuat   TEXT    NOT NULL DEFAULT (datetime('now')),
    diubah   TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS chats (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id  INTEGER REFERENCES users(id) ON DELETE CASCADE,
    mode     TEXT    NOT NULL CHECK (mode IN ('chat', 'jarvis')),
    judul    TEXT    NOT NULL DEFAULT 'Percakapan baru',
    dibuat   TEXT    NOT NULL DEFAULT (datetime('now')),
    diarsip  INTEGER NOT NULL DEFAULT 0 CHECK (diarsip IN (0, 1))
);

CREATE TABLE IF NOT EXISTS messages (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    role    TEXT    NOT NULL CHECK (role IN ('user', 'assistant', 'system', 'tool')),
    isi     TEXT    NOT NULL,
    token   INTEGER,
    ts      TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS attachments (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    nama       TEXT    NOT NULL,
    mime       TEXT,
    ukuran     INTEGER,
    jalur      TEXT    NOT NULL,
    ts         TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS actions (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id  INTEGER REFERENCES chats(id) ON DELETE SET NULL,
    perintah TEXT    NOT NULL,
    keluaran TEXT,
    status   TEXT    NOT NULL DEFAULT 'antre'
             CHECK (status IN ('antre', 'jalan', 'sukses', 'gagal')),
    ts       TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS settings (
    kunci TEXT PRIMARY KEY,
    nilai TEXT NOT NULL,
    ts    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_msg_chat  ON messages(chat_id, ts);
CREATE INDEX IF NOT EXISTS idx_chat_user ON chats(user_id, dibuat DESC);
CREATE INDEX IF NOT EXISTS idx_att_msg   ON attachments(message_id);
CREATE INDEX IF NOT EXISTS idx_act_chat  ON actions(chat_id, ts);

CREATE TRIGGER IF NOT EXISTS trg_user_ubah
AFTER UPDATE ON users
BEGIN
    UPDATE users SET diubah = datetime('now') WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_chat_auto_judul
AFTER INSERT ON messages
WHEN NEW.role = 'user'
BEGIN
    UPDATE chats
       SET judul = substr(NEW.isi, 1, 60)
     WHERE id = NEW.chat_id
       AND judul IN ('', 'Percakapan baru');
END;

CREATE VIEW IF NOT EXISTS v_ringkas_chat AS
SELECT c.id,
       c.mode,
       c.judul,
       u.nama AS pengguna,
       COUNT(m.id) AS jumlah_pesan,
       MAX(m.ts)   AS terakhir
  FROM chats c
  LEFT JOIN users u    ON u.id = c.user_id
  LEFT JOIN messages m ON m.chat_id = c.id
 GROUP BY c.id;
