'use strict';
// Dedicated alternate entry point. Never import a production .env file here.
const {validateReviewEnvironment,activateReviewRuntime,assertReviewDatabase}=require('./isolation');
async function main() {
  const config=validateReviewEnvironment();activateReviewRuntime(config);
  const {DB}=require('../db');
  await assertReviewDatabase(DB,config);
  const {startServer}=require('../server');
  const {createReviewFixtures}=require('./fixtures');
  const fixtures=createReviewFixtures(DB,config);
  // A module-owned registry binds HTTP adapters to the validated instance.
  require('./state').fixtures=fixtures;
  await startServer({startWorkers:false,beforeListen:()=>fixtures.refresh()});
  const timer=setInterval(()=>{void fixtures.refresh().catch(error=>{
    console.error('[Review] Fixture refresh failed; stopping isolated listener:',error.message);
    process.exit(1);
  });},5*60000);timer.unref();
}
if(require.main===module) main().catch(error=>{console.error(error.message);process.exit(1);});
module.exports={main};
