'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const script=fs.readFileSync(path.join(__dirname,'../review/init-schema.sh'),'utf8');
const expression=/sed '([^']+)' \/review-schema\/base\.sql/.exec(script)?.[1];
assert.ok(expression,'Read the exact filter used by the deployed initializer.');
assert.equal(expression,"/^-- Initial Seed Data[[:space:]]*$/,$d",'The deployed initializer must stop before public fixture rows.');
const source=fs.readFileSync(path.join(__dirname,'../init-db.sql'),'utf8').replace(/\r\n/g,'\n');
for(const input of [source,source.replace(/\n/g,'\r\n')]) {
  let output;
  const result=spawnSync('sed',[expression],{input,encoding:'utf8'});
  if(result.error?.code==='ENOENT') {
    // Windows development machines often have no POSIX sed. Mirror this exact
    // address expression so the portable regression still checks LF and CRLF.
    output=input.replace(/\r\n/g,'\n').split('\n').slice(0, input.replace(/\r\n/g,'\n').split('\n').findIndex(line=>/^-- Initial Seed Data\s*$/.test(line))).join('\n');
  } else {
    assert.equal(result.status,0,'POSIX sed must successfully filter the source schema.');
    output=result.stdout;
  }
  assert.match(output,/CREATE TABLE IF NOT EXISTS users/);
  assert.match(output,/CREATE TABLE IF NOT EXISTS audit_logs/);
  assert.doesNotMatch(output,/INSERT INTO (routes|route_stops|vehicles|notifications)/);
}
console.log('Review schema filter passed for LF and CRLF: DDL retained, public seed rows excluded.');
