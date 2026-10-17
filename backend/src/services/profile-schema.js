const { DB } = require('../db');

let initialization;
function initProfileSchema() {
  if (!initialization) {
    initialization = (async () => {
      await DB.exec(`CREATE TABLE IF NOT EXISTS user_profiles (
        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        emergency_contact_name TEXT NOT NULL DEFAULT '',
        emergency_contact_phone TEXT NOT NULL DEFAULT '',
        vehicle_color TEXT NOT NULL DEFAULT '',
        updated_at TEXT NOT NULL
      )`);
      await DB.exec(`CREATE TABLE IF NOT EXISTS user_profile_documents (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        kind TEXT NOT NULL,
        storage_name TEXT NOT NULL,
        content_type TEXT NOT NULL,
        byte_size INTEGER NOT NULL,
        uploaded_at TEXT NOT NULL,
        PRIMARY KEY(user_id, kind)
      )`);
    })().catch(err => { initialization = null; throw err; });
  }
  return initialization;
}

module.exports = { initProfileSchema };
