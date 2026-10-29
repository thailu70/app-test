'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const script=fs.readFileSync(path.join(__dirname,'../review/init-schema.sh'),'utf8');
const expression=/sed '([^']+)' \/review-schema\/base\.sql/.exec(script)?.[1];
assert.ok(expression,'Read the exact filter used by the deployed initializer.');
const source=fs.readFileSync(path.join(__dirname,'../init-db.sql'),'utf8').replace(/\r\n/g,'\n');
for(const input of [source,source.replace(/\n/g,'\r\n')]) {
  const result=spawnSync('sed',[expression],{input,encoding:'utf8'});
  assert.equal(result.status,0,'POSIX sed must successfully filter the source schema.');
  assert.match(result.stdout,/CREATE TABLE IF NOT EXISTS users/);
  assert.match(result.stdout,/CREATE TABLE IF NOT EXISTS audit_logs/);
  assert.doesNotMatch(result.stdout,/INSERT INTO (routes|route_stops|vehicles|notifications)/);
}
console.log('Review schema filter passed for LF and CRLF: DDL retained, public seed rows excluded.');
