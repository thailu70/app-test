/**
 * RoutePass / Transport Navigator - Database Backup Utility
 * Backs up SQLite and PostgreSQL databases safely before migrations or maintenance.
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
const BACKUP_DIR = path.join(DATA_DIR, 'backups');

function ensureDir(dir) {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
}

function backupDatabase() {
  ensureDir(BACKUP_DIR);
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const results = { timestamp, files: [] };

  // 1. SQLite Backup
  const sqliteFile = path.join(DATA_DIR, 'transport.db');
  if (fs.existsSync(sqliteFile)) {
    const backupTarget = path.join(BACKUP_DIR, `transport_${timestamp}.db.backup`);
    fs.copyFileSync(sqliteFile, backupTarget);
    results.files.push({ type: 'sqlite', path: backupTarget, sizeBytes: fs.statSync(backupTarget).size });
    console.log(`[Backup] SQLite database backed up to: ${backupTarget}`);
  }

  // 2. PostgreSQL Dump (if DATABASE_URL is set and pg_dump is available)
  if (process.env.DATABASE_URL) {
    try {
      const pgTarget = path.join(BACKUP_DIR, `postgres_${timestamp}.sql`);
      console.log(`[Backup] Attempting pg_dump to ${pgTarget}...`);
      execSync(`pg_dump "${process.env.DATABASE_URL}" -f "${pgTarget}"`, { stdio: 'ignore' });
      results.files.push({ type: 'postgres', path: pgTarget, sizeBytes: fs.statSync(pgTarget).size });
      console.log(`[Backup] PostgreSQL backup created: ${pgTarget}`);
    } catch (err) {
      console.warn(`[Backup] Note: pg_dump not available in current shell, skipping pg_dump: ${err.message}`);
    }
  }

  return results;
}

if (require.main === module) {
  try {
    const res = backupDatabase();
    console.log('[Backup] Completed successfully:', JSON.stringify(res, null, 2));
  } catch (err) {
    console.error('[Backup Error]:', err.message);
    process.exit(1);
  }
}

module.exports = { backupDatabase };
