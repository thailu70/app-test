(function (root, factory) {
  const portal = factory();
  if (typeof module === 'object' && module.exports) module.exports = portal;
  if (root?.document) root.document.addEventListener('DOMContentLoaded', () => portal.mount(root));
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';
  const words = {
    skip:['Skip to content','ወደ ይዘቱ ይሂዱ','Gara qabiyyeetti darbi'], logout:['Sign out','ውጣ','Baʼi'], joinLabel:['DRIVERS & TRANSPORT COMPANIES','ሹፌሮች እና የትራንስፖርት ኩባንያዎች','KONKOLAACHISAAFI DHAABBILEE GEEJJIBAA'], heroEyebrow:['PEOPLE. PLACES. POSSIBILITIES.','ሰዎች። ቦታዎች። ዕድሎች።','NAMOOTA. BAKKEEWWAN. CARROOWWAN.'], heroTitle:['Your next chapter starts with Passo.','ቀጣዩ ጉዞዎ በፓሶ ይጀምራል።','Imalli keessan itti aanu Passo waliin jalqaba.'], heroBody:['Bring your experience. Connect communities. Let’s go further, together.','በልምድዎ ማህበረሰቦችን ያገናኙ። አብረን ወደ ፊት እንሂድ።','Muuxannoo keessaniin hawaasa wal qunnamsiisaa. Waliin fagoo haa imallu.'], welcome:['WELCOME TO PASSO','ወደ ፓሶ እንኳን ደህና መጡ','BAGA GARA PASSO DHUFTAN'], createTitle:['Let’s get you started.','እንጀምር።','Haa jalqabnu.'], createIntro:['Create an account now. Verify your phone and add your documents afterwards.','መጀመሪያ መለያ ይፍጠሩ። ከዚያ ስልክዎን ያረጋግጡ እና ሰነዶችን ይስቀሉ።','Dura herrega banadhaa. Itti aansuun bilbila mirkaneessaa, sanadootas galchaa.'], loginTitle:['Good to see you again.','እንኳን ደህና መጡ።','Baga nagaan deebitan.'], loginIntro:['Sign in to continue your application and check its progress.','ማመልከቻዎን ለመቀጠል እና ሁኔታውን ለማየት ይግቡ።','Iyyata keessan itti fufuufi haala isaa ilaaluuf seenaa.'], driver:['Driver','ሹፌር','Konkolaachisaa'], company:['Transport company','የትራንስፖርት ኩባንያ','Dhaabbata geejjibaa'], signUp:['Create account','መለያ ፍጠር','Herrega bani'], signIn:['Sign in','ግባ','Seeni'], companyName:['Company name','የኩባንያ ስም','Maqaa dhaabbataa'], fullName:['Full name','ሙሉ ስም','Maqaa guutuu'], representative:['Representative’s full name','የተወካይ ሙሉ ስም','Maqaa guutuu bakka buʼaa'], phone:['Mobile phone number','የሞባይል ስልክ ቁጥር','Lakkoofsa bilbila harkaa'], password:['Password','የይለፍ ቃል','Jecha darbii'], passwordHelp:['Use at least 10 characters.','ቢያንስ 10 ቁምፊዎች ይጠቀሙ።','Yoo xiqqaate arfiilee 10 fayyadamaa.'], show:['Show','አሳይ','Agarsiisi'], hide:['Hide','ደብቅ','Dhoksii'], serviceType:['How would you like to drive?','በየትኛው አገልግሎት መሥራት ይፈልጋሉ?','Tajaajila kamiin hojjechuu barbaaddu?'], dailyOption:['Daily commute · my own vehicle','ዕለታዊ ጉዞ · የራሴ ተሽከርካሪ','Imala guyyaa · konkolaataa dhuunfaa'], intercityOption:['Intercity · a company vehicle','የከተማ መካከል ጉዞ · የኩባንያ ተሽከርካሪ','Magaalaa gidduu · konkolaataa dhaabbataa'], driverCompany:['Your transport company','የትራንስፖርት ኩባንያዎ','Dhaabbata geejjibaa keessan'], chooseCompany:['Choose your company','ኩባንያዎን ይምረጡ','Dhaabbata keessan filadhaa'], licenseNumber:['Driving licence number','የመንጃ ፈቃድ ቁጥር','Lakkoofsa hayyama konkolaachisummaa'], yourVehicle:['Your vehicle','የእርስዎ ተሽከርካሪ','Konkolaataa keessan'], plate:['Plate number','የሰሌዳ ቁጥር','Lakkoofsa gabatee'], model:['Make / model','አምራች / ሞዴል','Gosa / moodeela'], vehicleType:['Vehicle type','የተሽከርካሪ ዓይነት','Gosa konkolaataa'], chooseType:['Choose a vehicle type','የተሽከርካሪ ዓይነት ይምረጡ','Gosa konkolaataa filadhaa'], seats:['seats','መቀመጫዎች','teessoo'], retry:['Try again','እንደገና ይሞክሩ','Irra deebiʼaa'], createButton:['Create my account','መለያዬን ፍጠር','Herrega koo bani'], loginButton:['Sign in to my account','ወደ መለያዬ ግባ','Gara herrega kootti seeni'], accountNote:['Your application is reviewed before any passenger or route assignment.','ተሳፋሪ ወይም መስመር ከመመደቡ በፊት ማመልከቻዎ ይገመገማል።','Iyyanni keessan osoo imaltoonni yookaan daandiin hin ramadamin dura ilaalama.'], invitedCompany:['Have a company invitation?','የኩባንያ ግብዣ አለዎት?','Affeerraa dhaabbataa qabduu?'], companyDashboard:['Company dashboard','የኩባንያ ፖርታል','Poortaalii dhaabbataa'], stepAccount:['Create your account','መለያዎን ይፍጠሩ','Herrega keessan bani'], stepAccountBody:['Your phone and password get you started.','በስልክ ቁጥር እና የይለፍ ቃል ይጀምሩ።','Bilbilaafi jecha darbiin jalqabaa.'], stepDetails:['Complete your details','መረጃዎን ያሟሉ','Odeeffannoo keessan guutaa'], stepDetailsBody:['Verify your phone and upload your documents.','ስልክዎን ያረጋግጡ እና ሰነዶችዎን ይስቀሉ።','Bilbila mirkaneessaa, sanadoota galchaa.'], stepReview:['Send for review','ለግምገማ ይላኩ','Akka ilaalamuuf ergaa'], stepReviewBody:['We’ll review your application before assignment.','ከምደባ በፊት ማመልከቻዎ ይገመገማል።','Iyyanni keessan ramaddii dura ilaalama.'], lookingTravel:['Looking for intercity travel?','የከተማ መካከል ጉዞ ይፈልጋሉ?','Imala magaalaa gidduu barbaadduu?'], applicationLabel:['YOUR PASSO APPLICATION','የፓሶ ማመልከቻዎ','IYYATA PASSO KEESSAN'], refresh:['Refresh status','ሁኔታውን አድስ','Haala haaromsi'], verifyPhone:['Verify phone','ስልክ ያረጋግጡ','Bilbila mirkaneessaa'], yourDetails:['Your details','የእርስዎ መረጃ','Odeeffannoo keessan'], documents:['Documents','ሰነዶች','Sanadoota'], submitReview:['Send for review','ለግምገማ ላክ','Akka ilaalamuuf ergi'], stepOne:['STEP 1','ደረጃ 1','TARKAANFII 1'], stepTwo:['STEP 2','ደረጃ 2','TARKAANFII 2'], stepThree:['STEP 3','ደረጃ 3','TARKAANFII 3'], stepFour:['STEP 4','ደረጃ 4','TARKAANFII 4'], otpCode:['SMS verification code','በSMS የተላከ የማረጋገጫ ኮድ','Koodii mirkaneessaa SMS'], sendCode:['Send code','ኮድ ላክ','Koodii ergi'], verifyButton:['Verify my phone','ስልኬን አረጋግጥ','Bilbila koo mirkaneessi'], emergencyName:['Emergency contact name','የአደጋ ጊዜ ተጠሪ ስም','Maqaa nama yeroo balaa qunnamamu'], emergencyPhone:['Emergency contact phone','የአደጋ ጊዜ ተጠሪ ስልክ','Bilbila nama yeroo balaa qunnamamu'], vehicleColor:['Vehicle colour','የተሽከርካሪ ቀለም','Halluu konkolaataa'], companyAddress:['Company address (optional)','የኩባንያ አድራሻ (አማራጭ)','Teessoo dhaabbataa (dirqama miti)'], registrationNumber:['Business registration number (optional)','የንግድ ምዝገባ ቁጥር (አማራጭ)','Lakkoofsa galmee daldalaa (dirqama miti)'], saveDetails:['Save details','መረጃ አስቀምጥ','Odeeffannoo olkaaʼi'], documentsHelp:['Upload a clear JPEG, PNG or PDF, up to 5 MB each. Profile photos must be JPEG or PNG.','ግልጽ JPEG፣ PNG ወይም PDF ይስቀሉ፤ እያንዳንዱ እስከ 5 MB። የመለያ ፎቶ JPEG ወይም PNG ብቻ ነው።','JPEG, PNG yookaan PDF ifa taʼe galchaa; tokkoon tokkoon hanga 5 MB. Suuraan dhuunfaa JPEG yookaan PNG qofa.'], readyReview:['Ready for the next step?','ለቀጣዩ ደረጃ ዝግጁ ነዎት?','Tarkaanfii itti aanuuf qophooftanii?'], allocationNote:['Phone verification, required documents and approval are needed before passenger allocation.','ተሳፋሪዎች ከመመደባቸው በፊት የስልክ ማረጋገጫ፣ ሰነዶች እና ፈቃድ ያስፈልጋሉ።','Ramaddii imaltootaa dura mirkaneessi bilbilaa, sanadoonni barbaachisaniifi raggaasisni ni barbaachisu.'], verified:['Verified','ተረጋግጧል','Mirkanaaʼe'], notVerified:['Not verified','አልተረጋገጠም','Hin mirkanoofne'], uploaded:['Uploaded','ተሰቅሏል','Galeera'], missing:['Not uploaded','አልተሰቀለም','Hin galchine'], selectFile:['Choose document','ሰነድ ይምረጡ','Sanada filadhaa'], upload:['Upload','ስቀል','Galchi'], replace:['Replace','ቀይር','Jijjiiri'], download:['Download','አውርድ','Buusi'], DRAFT:['In progress','በሂደት ላይ','Hojii irra jira'], SUBMITTED:['Under review','በግምገማ ላይ','Ilaalamaa jira'], APPROVED:['Approved','ጸድቋል','Raggaʼe'], REJECTED:['Changes needed','ማስተካከያ ያስፈልጋል','Fooyyaʼiinsi barbaachisa'], progressTitle:['A few steps. A new beginning.','ጥቂት ደረጃዎች። አዲስ ጅማሬ።','Tarkaanfii muraasa. Jalqaba haaraa.'], progressBody:['Complete the steps below, then send your application for review.','የሚከተሉትን ደረጃዎች ያሟሉ እና ማመልከቻዎን ለግምገማ ይላኩ።','Tarkaanfiiwwan armaan gadii xumuraatii iyyata keessan ergaa.'], reviewPending:['Your application has been submitted. Check here for updates.','ማመልከቻዎ ተልኳል። አዳዲስ መረጃዎችን እዚህ ይመልከቱ።','Iyyanni keessan ergameera. Odeeffannoo haaraa asitti ilaalaa.'], approvalBody:['Your application is approved. Sign in to the Passo app or company dashboard for your assignments.','ማመልከቻዎ ጸድቋል። ምደባዎትን ለማየት ወደ ፓሶ መተግበሪያ ወይም የኩባንያ ፖርታል ይግቡ።','Iyyanni keessan raggaʼeera. Ramaddii keessan ilaaluuf appii Passo yookaan poortaalii dhaabbataatti seenaa.'], reviewReady:['Your details are ready. Send your application for review.','መረጃዎ ተሟልቷል። ማመልከቻዎን ለግምገማ ይላኩ።','Odeeffannoon keessan guutameera. Iyyata keessan ergaa.'], reviewIncomplete:['Complete these items before sending your application:','ማመልከቻዎን ከመላክዎ በፊት እነዚህን ያሟሉ፦','Iyyata erguu dura kanneen xumuraa:'], wait:['Please wait…','እባክዎ ይጠብቁ…','Maaloo eegaa…'], networkError:['We couldn’t connect. Check your connection and try again.','መገናኘት አልተቻለም። ግንኙነትዎን ያረጋግጡ እና እንደገና ይሞክሩ።','Wal qunnamuu hin dandeenye. Intarneetii keessan ilaalaa, irra deebiʼaa.'], genericError:['We couldn’t complete this. Please try again.','ይህን ማጠናቀቅ አልተቻለም። እንደገና ይሞክሩ።','Kana xumuruu hin dandeenye. Irra deebiʼaa.'], expired:['Your session has ended. Please sign in again.','ክፍለ ጊዜዎ አብቅቷል። እንደገና ይግቡ።','Yeroon seensaa dhumeera. Irra deebiʼaa seenaa.'], saved:['Your details have been saved.','መረጃዎ ተቀምጧል።','Odeeffannoon keessan olkaaʼameera.'], fileType:['Choose a JPEG, PNG or PDF document.','JPEG፣ PNG ወይም PDF ሰነድ ይምረጡ።','Sanada JPEG, PNG yookaan PDF filadhaa.'], photoType:['Choose a JPEG or PNG photo.','JPEG ወይም PNG ፎቶ ይምረጡ።','Suuraa JPEG yookaan PNG filadhaa.'], fileSize:['Each file must be between 1 byte and 5 MB.','እያንዳንዱ ፋይል ከ1 ባይት እስከ 5 MB መሆን አለበት።','Faayiliin tokko baayitii 1 hanga 5 MB taʼuu qaba.'], codeSent:['We sent an SMS code to your phone.','የSMS ኮድ ወደ ስልክዎ ተልኳል።','Koodiin SMS gara bilbila keessaniitti ergameera.'], codeNotSent:['The SMS could not be sent. Please try again later.','SMS መላክ አልተቻለም። ቆይተው ይሞክሩ።','SMS erguun hin dandaʼamne. Booda irra deebiʼaa.'], phoneInstruction:['We’ll send a verification code to this account’s phone number.','የማረጋገጫ ኮድ ወደ ዚህ መለያ ስልክ ቁጥር እንልካለን።','Koodii mirkaneessaa gara bilbila herrega kanaatti ergina.'], noTypes:['No vehicle types are available. Please try again later.','የተሽከርካሪ ዓይነቶች አልተገኙም። ቆይተው ይሞክሩ።','Gosti konkolaataa hin argamne. Booda irra deebiʼaa.'], noCompanies:['No companies are available. Please try again later.','ኩባንያዎች አልተገኙም። ቆይተው ይሞክሩ።','Dhaabbileen hin argamne. Booda irra deebiʼaa.'], PHONE_VERIFICATION:['Phone verification','የስልክ ማረጋገጫ','Mirkaneessa bilbilaa'], LICENSE:['Driving licence','መንጃ ፈቃድ','Hayyama konkolaachisummaa'], VEHICLE_REGISTRATION:['Vehicle registration','የተሽከርካሪ ምዝገባ','Galmee konkolaataa'], INSURANCE:['Vehicle insurance','የተሽከርካሪ ኢንሹራንስ','Inshuraansii konkolaataa'], PHOTO:['Profile photo','የመለያ ፎቶ','Suuraa dhuunfaa'], BUSINESS_LICENSE:['Business licence','የንግድ ፈቃድ','Hayyama daldalaa'], REGISTRATION:['Company registration','የኩባንያ ምዝገባ','Galmee dhaabbataa'], REPRESENTATIVE_ID:['Representative’s ID','የተወካይ መታወቂያ','Waraqaa eenyummaa bakka buʼaa'], EMERGENCY_CONTACT_NAME:['Emergency contact name','የአደጋ ጊዜ ተጠሪ ስም','Maqaa nama yeroo balaa qunnamamu'], EMERGENCY_CONTACT_PHONE:['Emergency contact phone','የአደጋ ጊዜ ተጠሪ ስልክ','Bilbila nama yeroo balaa qunnamamu'], VEHICLE_COLOR:['Vehicle colour','የተሽከርካሪ ቀለም','Halluu konkolaataa']
  };
  Object.assign(words, {
    support:['Need help? Contact info@passo.live','እርዳታ ይፈልጋሉ? info@passo.live ያግኙ','Gargaarsa barbaadduu? info@passo.live qunnamaa'],
    dataUse:['We use your contact and vehicle or company details to review your application. Phone verification is required before passenger allocation.','ማመልከቻዎን ለመገምገም የመገናኛ እና የተሽከርካሪ ወይም የኩባንያ መረጃዎን እንጠቀማለን። ከተሳፋሪ ምደባ በፊት ስልክ ማረጋገጥ ያስፈልጋል።','Iyyata keessan ilaaluuf odeeffannoo qunnamtii fi konkolaataa yookaan dhaabbataa fayyadamna. Ramaddii imaltootaa dura bilbila mirkaneessuun barbaachisa.'],
    privateDocuments:['Uploaded documents are available only to authorized administrators or your company’s reviewers for application review.','የተሰቀሉ ሰነዶች ማመልከቻውን እንዲገመግሙ ፈቃድ ላላቸው አስተዳዳሪዎች ወይም ለኩባንያዎ ገምጋሚዎች ብቻ ይታያሉ።','Sanadoota galfaman bulchitoonni yookaan ilaaltotni dhaabbata keessanii hayyamaman qofti iyyata ilaaluuf arguu dandaʼu.']
  });
  const languageIndex = { en:0, am:1, om:2 };
  const translate = (key, language = 'en') => words[key]?.[languageIndex[language] ?? 0] || words[key]?.[0] || key;
  const escape = value => String(value ?? '').replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const uploadProblem = (file, kind) => !file || !Number.isFinite(file.size) || file.size <= 0 || file.size > 5*1024*1024 ? 'fileSize' : !(kind === 'PHOTO' ? ['image/jpeg','image/png'] : ['image/jpeg','image/png','application/pdf']).includes(file.type) ? kind === 'PHOTO' ? 'photoType' : 'fileType' : null;
  function accountPayload(role, mode, values) {
    const clean=key=>String(values[key]||'').trim();
    const payload={role,phone:clean('phone'),password:values.password};
    if(mode!=='register')return payload;
    payload.fullName=clean('fullName');
    if(role==='COMPANY')payload.companyName=clean('companyName');
    else {
      payload.serviceType=values.serviceType;payload.licenseNumber=clean('licenseNumber');
      if(payload.serviceType==='INTERCITY')payload.companyId=clean('companyId');
      else for(const key of ['assignedVehiclePlate','vehicleModel','vehicleType'])payload[key]=clean(key);
    }
    return payload;
  }
  function createApiClient({ fetchFn, getToken, onUnauthorized = () => {} }) {
    return async function api(path, options = {}) {
      if (!/^\/(?:register|login|me|profile|request-otp|verify-otp|submit|documents\/[A-Z_]+)$/.test(path)) throw new Error('Invalid onboarding API path.');
      const sentToken = options.anonymous ? null : getToken();
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 30000);
      try {
        const headers = { ...(sentToken ? {Authorization:'Bearer '+sentToken} : {}), ...(options.raw ? {'Content-Type':options.raw.type} : {'Content-Type':'application/json'}) };
        const response = await fetchFn('/api/onboarding'+path, { method:options.method || 'GET', headers, credentials:'omit', redirect:'error', signal:controller.signal,
          ...(options.raw ? {body:options.raw} : options.body !== undefined ? {body:JSON.stringify(options.body)} : {}) });
        let payload;
        if (options.blob && response.ok) return await response.blob();
        try { payload = await response.json(); } catch (_) { payload = {}; }
        if (response.status === 401 && sentToken && sentToken === getToken()) onUnauthorized();
        if (!response.ok || payload.success === false) throw Object.assign(new Error(typeof payload.error === 'string' ? payload.error : 'Request failed.'), {status:response.status, server:true});
        return payload;
      } catch (error) {
        if (error.server) throw error;
        throw Object.assign(new Error('Connection failed.'), {network:true});
      } finally { clearTimeout(timeout); }
    };
  }

  function mount(window) {
    const doc=window.document, $=selector=>doc.querySelector(selector), all=selector=>[...doc.querySelectorAll(selector)];
    const TOKEN_KEY='passo.onboarding.token';
    let token='', role='DRIVER', mode='register', language='en', snapshot=null, revision=0, otpUntil=0, otpTimer=null;
    const files=new Map(), catalog={types:[],companies:[]};
    try { token=window.sessionStorage.getItem(TOKEN_KEY)||''; language=window.sessionStorage.getItem('passo.join.language')||'en'; } catch (_) {}
    if (!(language in languageIndex)) language='en';
    const query=new URLSearchParams(window.location.search);
    if(['driver','company'].includes(query.get('role')))role=query.get('role').toUpperCase();
    if(query.get('view')==='login')mode='login';
    const t=key=>translate(key,language);
    function message(selector,text,error=false) { const element=$(selector); element.textContent=text||''; element.classList.toggle('error',error); }
    function errorMessage(error) { return error.network?t('networkError'):error.server?error.message:t('genericError'); }
    function setToken(value) { token=value; revision++; try { if(value)window.sessionStorage.setItem(TOKEN_KEY,value);else window.sessionStorage.removeItem(TOKEN_KEY); } catch (_) {} }
    function signOut(expired=false) {
      setToken(''); snapshot=null; files.clear(); otpUntil=0; clearInterval(otpTimer); otpTimer=null;
      $('#accountForm').reset(); $('#profileForm').reset(); $('#otpForm').reset(); $('#password').type='password';
      all('#dashboard button').forEach(button=>{button.disabled=false;button.setAttribute('aria-busy','false');});
      $('#requestOtp').textContent=t('sendCode');
      $('#entry').hidden=false; $('#dashboard').hidden=true; $('#logout').hidden=true; mode='login';
      $('#accountSubmit').disabled=false;
      for(const id of ['#accountMessage','#otpMessage','#profileMessage','#documentMessage','#dashboardMessage','#reviewMessage'])message(id,'');
      $('#documentCards').replaceChildren();delete $('#documentCards').dataset.renderKey; updateAuth(); if(expired)message('#accountMessage',t('expired'),true);
    }
    const api=createApiClient({fetchFn:window.fetch.bind(window),getToken:()=>token,onUnauthorized:()=>signOut(true)});
    function busy(element,value) { element.disabled=value; element.setAttribute('aria-busy',String(value)); }
    function translatePage() {
      doc.documentElement.lang=language;
      all('[data-i18n]').forEach(el=>{ el.textContent=t(el.dataset.i18n); });
      all('[data-language]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.language===language)));
      updateAuth(); renderOptions(); if(snapshot)renderDashboard(false);
    }
    function updateAuth() {
      all('[data-role]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.role===role)));
      const registration=mode==='register', driver=role==='DRIVER', daily=$('#serviceType').value==='DAILY_COMMUTE';
      all('[data-registration]').forEach(el=>{ el.hidden=!registration || (el.hasAttribute('data-company')&&driver) || (el.hasAttribute('data-driver')&&!driver) || (el.hasAttribute('data-daily')&&!daily) || (el.hasAttribute('data-intercity')&&daily); el.querySelectorAll('input,select').forEach(input=>{input.disabled=el.hidden;input.required=!el.hidden;}); });
      $('#companyName').required=registration&&!driver; $('#fullName').required=registration;
      $('#registerTab').setAttribute('aria-pressed',String(registration));$('#loginTab').setAttribute('aria-pressed',String(!registration));
      $('#formTitle').textContent=t(registration?'createTitle':'loginTitle'); $('#formIntro').textContent=t(registration?'createIntro':'loginIntro');
      $('#fullNameLabel').textContent=t(driver?'fullName':'representative'); $('#accountSubmit span').textContent=t(registration?'createButton':'loginButton');
      $('#password').minLength=registration?10:1; $('#password').autocomplete=registration?'new-password':'current-password';
      $('#showPassword').textContent=t($('#password').type==='password'?'show':'hide');
    }
    function renderOptions() {
      for (const [selector,rows,placeholder] of [['#vehicleType',catalog.types,'chooseType'],['#driverCompany',catalog.companies,'chooseCompany']]) {
        const select=$(selector), value=select.value;
        select.innerHTML='<option value="">'+escape(t(placeholder))+'</option>'+rows.map(row=>'<option value="'+escape(row.code||row.id)+'">'+escape(row.name)+(row.capacity?' · '+escape(row.capacity)+' '+escape(t('seats')):'')+'</option>').join('');
        if(rows.some(row=>(row.code||row.id)===value))select.value=value;
      }
    }
    async function loadOptions(kind) {
      const types=kind==='types', status=types?'#typeLookupStatus':'#companyLookupStatus', retry=types?'#retryTypes':'#retryCompanies';
      message(status,t('wait'));$(retry).hidden=true;
      try {
        const response=await window.fetch(types?'/api/master-data/vehicle-types':'/api/companies',{credentials:'omit',redirect:'error',signal:AbortSignal.timeout(15000)});
        const data=await response.json();if(!response.ok)throw new Error('Options unavailable.');
        const rows=types?data.vehicleTypes:data.companies;if(!Array.isArray(rows))throw new Error('Invalid options.');
        catalog[kind]=rows.filter(row=>types?row.active!==false:!row.status||row.status==='ACTIVE'); renderOptions();
        message(status,catalog[kind].length?'':t(types?'noTypes':'noCompanies'));$(retry).hidden=catalog[kind].length>0;
      } catch (_) { message(status,t('networkError'),true);$(retry).hidden=false; }
    }
    async function refresh(fill=true) {
      const current=revision;const result=await api('/me');if(current!==revision)return;
      if(!result.account||!result.onboarding)throw new Error('Invalid application response.');snapshot=result;role=result.account.role;renderDashboard(fill);
    }
    function renderDashboard(fill=true) {
      if(!snapshot)return;
      const {account,onboarding:o}=snapshot, profile=o.profile||{}, status=o.reviewStatus||'DRAFT';
      $('#entry').hidden=true;$('#dashboard').hidden=false;$('#logout').hidden=false;
      $('#dashboardTitle').textContent=account.fullName||account.companyName||t(role==='DRIVER'?'driver':'company');
      $('#accountIdentity').textContent=[t(role==='DRIVER'?'driver':'company'),account.companyName,account.phone].filter(Boolean).join(' · ');
      $('#reviewBadge').textContent=t(status);$('#progressTitle').textContent=status==='APPROVED'?t('APPROVED'):status==='SUBMITTED'?t('SUBMITTED'):t('progressTitle');
      $('#progressBody').textContent=status==='APPROVED'?t('approvalBody'):status==='SUBMITTED'?t('reviewPending'):t('progressBody');
      const missing=Array.isArray(o.missing)?o.missing:[], documents=o.documents||[], required=o.requiredDocuments||[];
      const complete={phone:o.phoneVerified===true,profile:!missing.some(key=>['EMERGENCY_CONTACT_NAME','EMERGENCY_CONTACT_PHONE','VEHICLE_COLOR'].includes(key)),documents:required.length>0&&required.every(kind=>documents.some(item=>item.kind===kind)),submitted:Boolean(o.submittedAt)||status==='APPROVED'};
      $('#progressCount').textContent=Object.values(complete).filter(Boolean).length+' / 4';
      all('[data-check]').forEach(icon=>icon.closest('a').classList.toggle('done',complete[icon.dataset.check]));
      $('#phoneBadge').textContent=t(complete.phone?'verified':'notVerified');$('#phoneBadge').classList.toggle('warning',!complete.phone);
      $('#phoneSummary').textContent=complete.phone?(account.phone+' · '+t('verified')):t('phoneInstruction')+' '+account.phone;
      $('#otpForm').hidden=complete.phone;
      const driver=role==='DRIVER';all('[data-driver-profile]').forEach(el=>{el.hidden=!driver;el.querySelector('input').disabled=!driver;el.querySelector('input').required=driver;});
      all('[data-company-profile]').forEach(el=>{el.hidden=driver;el.querySelector('input').disabled=driver;});
      if(fill)for(const key of ['emergencyContactName','emergencyContactPhone','vehicleColor','address','registrationNumber'])$('#'+key).value=profile[key]||'';
      const profileKey=JSON.stringify({role,language,documents,required});
      if($('#documentCards').dataset.renderKey!==profileKey){$('#documentCards').dataset.renderKey=profileKey;renderDocuments(required,documents);}
      $('#reviewSummary').textContent=status==='APPROVED'?t('approvalBody'):status==='SUBMITTED'?t('reviewPending'):o.ready?t('reviewReady'):t('reviewIncomplete');
      $('#missingItems').innerHTML=missing.map(key=>'<li>'+escape(t(key))+'</li>').join('');
      $('#submitReview').disabled=!o.ready||status==='SUBMITTED'||status==='APPROVED';
      $('#submitReview span').textContent=t(status==='SUBMITTED'?'SUBMITTED':status==='APPROVED'?'APPROVED':'submitReview');
      if(status==='REJECTED'&&o.reviewNote)message('#reviewMessage',o.reviewNote,true);
    }
    function renderDocuments(required,documents) {
      $('#documentCards').innerHTML=required.map(kind=>{
        const uploaded=documents.some(item=>item.kind===kind),selected=files.get(kind);
        return '<section class="document-card '+(uploaded?'uploaded':'')+'" data-document="'+escape(kind)+'"><svg class="doc-icon"><use href="#icon-file"/></svg><h3>'+escape(t(kind))+'</h3><span class="pill '+(uploaded?'':'warning')+'">'+escape(t(uploaded?'uploaded':'missing'))+'</span><label for="file-'+escape(kind)+'">'+escape(t('selectFile'))+'</label><input type="file" id="file-'+escape(kind)+'" data-file="'+escape(kind)+'" accept="image/jpeg,image/png'+(kind==='PHOTO'?'':',application/pdf')+'"><p data-filename>'+escape(selected?.name||'')+'</p><div class="upload-actions"><button type="button" class="primary" data-upload="'+escape(kind)+'" '+(selected?'':'disabled')+'>'+escape(t(uploaded?'replace':'upload'))+'</button>'+(uploaded?'<button type="button" class="secondary" data-download="'+escape(kind)+'">'+escape(t('download'))+'</button>':'')+'</div><p class="doc-message" aria-live="polite"></p></section>';
      }).join('');
    }
    async function run(button,selector,operation,successKey) {
      const current=revision;busy(button,true);message(selector,'');
      try { const result=await operation();if(current!==revision)return;if(result.account&&result.onboarding){snapshot=result;renderDashboard(false);}else await refresh(false);if(current!==revision)return;message(selector,successKey?t(successKey):result.message||''); }
      catch(error){if(current===revision)message(selector,errorMessage(error),true);}
      finally{if(current===revision){busy(button,false);if(snapshot)renderDashboard(false);}}
    }
    all('[data-language]').forEach(button=>button.addEventListener('click',()=>{language=button.dataset.language;try{window.sessionStorage.setItem('passo.join.language',language);}catch(_){}translatePage();}));
    all('[data-role]').forEach(button=>button.addEventListener('click',()=>{role=button.dataset.role;message('#accountMessage','');updateAuth();}));
    $('#serviceType').addEventListener('change',updateAuth);
    $('#registerTab').addEventListener('click',()=>{mode='register';message('#accountMessage','');updateAuth();});
    $('#loginTab').addEventListener('click',()=>{mode='login';message('#accountMessage','');updateAuth();});
    $('#showPassword').addEventListener('click',()=>{const show=$('#password').type==='password';$('#password').type=show?'text':'password';$('#showPassword').setAttribute('aria-pressed',String(show));$('#showPassword').textContent=t(show?'hide':'show');});
    $('#retryTypes').addEventListener('click',()=>loadOptions('types'));$('#retryCompanies').addEventListener('click',()=>loadOptions('companies'));
    $('#accountForm').addEventListener('submit',async event=>{
      event.preventDefault();const form=event.currentTarget;if(!form.reportValidity())return;
      const payload=accountPayload(role,mode,Object.fromEntries([...form.elements].filter(input=>input.name).map(input=>[input.name,input.value])));
      const current=revision;busy($('#accountSubmit'),true);message('#accountMessage','');
      try {const result=await api(mode==='register'?'/register':'/login',{method:'POST',body:payload,anonymous:true});if(current!==revision)return;
        if(!result.token||!result.account||!result.onboarding)throw new Error('Invalid application response.');setToken(result.token);snapshot=result;role=result.account.role;$('#password').value='';$('#password').type='password';renderDashboard();$('#dashboardTitle').tabIndex=-1;$('#dashboardTitle').focus();}
      catch(error){if(current===revision)message('#accountMessage',errorMessage(error),true);}
      finally{busy($('#accountSubmit'),false);payload.password='';}
    });
    $('#logout').addEventListener('click',()=>signOut());
    $('#refreshDashboard').addEventListener('click',()=>run($('#refreshDashboard'),'#dashboardMessage',()=>api('/me')));
    $('#profileForm').addEventListener('submit',event=>{event.preventDefault();if(!event.currentTarget.reportValidity())return;const keys=role==='DRIVER'?['emergencyContactName','emergencyContactPhone','vehicleColor']:['address','registrationNumber'];const body=Object.fromEntries(keys.map(key=>[key,$('#'+key).value.trim()]));run($('#saveProfile'),'#profileMessage',()=>api('/profile',{method:'PATCH',body}),'saved');});
    $('#requestOtp').addEventListener('click',async()=>{
      const current=revision;busy($('#requestOtp'),true);message('#otpMessage','');
      try {const result=await api('/request-otp',{method:'POST',body:{}});if(current!==revision)return;
        const delivered=result.delivery==='SENT'||result.sent===true;
        message('#otpMessage',delivered?t('codeSent'):t('codeNotSent'),!delivered);
        if(delivered){otpUntil=Date.now()+Math.max(1,Number(result.retryAfterSeconds)||60)*1000;clearInterval(otpTimer);otpTimer=setInterval(()=>{if(Date.now()>=otpUntil){clearInterval(otpTimer);otpTimer=null;$('#requestOtp').disabled=false;$('#requestOtp').textContent=t('sendCode');}else $('#requestOtp').textContent=Math.ceil((otpUntil-Date.now())/1000)+'s';},1000);}
      }catch(error){if(current===revision)message('#otpMessage',errorMessage(error),true);}finally{if(current===revision&&Date.now()>=otpUntil)busy($('#requestOtp'),false);}
    });
    $('#otpForm').addEventListener('submit',event=>{event.preventDefault();if(!event.currentTarget.reportValidity())return;run($('#verifyOtp'),'#otpMessage',()=>api('/verify-otp',{method:'POST',body:{code:$('#otpCode').value.trim()}}),'verified');});
    $('#documentCards').addEventListener('change',event=>{
      const kind=event.target.dataset.file;if(!kind)return;const file=event.target.files?.[0],card=event.target.closest('[data-document]'),problem=uploadProblem(file,kind),status=card.querySelector('.doc-message');
      status.textContent=problem?t(problem):'';status.classList.toggle('error',Boolean(problem));
      if(problem){files.delete(kind);event.target.value='';}else files.set(kind,file);
      card.querySelector('[data-filename]').textContent=problem?'':file.name;card.querySelector('[data-upload]').disabled=Boolean(problem);
    });
    $('#documentCards').addEventListener('click',async event=>{
      const button=event.target.closest('button');if(!button)return;const kind=button.dataset.upload||button.dataset.download;if(!kind)return;
      const current=revision,card=button.closest('[data-document]'),status=card.querySelector('.doc-message');busy(button,true);status.textContent='';
      try {
        if(button.dataset.upload){const file=files.get(kind),problem=uploadProblem(file,kind);if(problem)throw new Error(t(problem));const result=await api('/documents/'+kind,{method:'PUT',raw:file});if(current!==revision)return;files.delete(kind);if(result.account&&result.onboarding){snapshot=result;renderDashboard(false);}else await refresh(false);}
        else {const blob=await api('/documents/'+kind,{blob:true});if(current!==revision)return;const url=window.URL.createObjectURL(blob),link=doc.createElement('a');link.href=url;link.download='Passo-'+kind.toLowerCase()+'.'+(blob.type==='application/pdf'?'pdf':blob.type==='image/png'?'png':'jpg');doc.body.append(link);link.click();link.remove();setTimeout(()=>window.URL.revokeObjectURL(url),1000);}
      }catch(error){if(current===revision){status.textContent=error.server||error.network?errorMessage(error):error.message;status.classList.add('error');}}finally{if(current===revision)busy(button,false);}
    });
    $('#submitReview').addEventListener('click',()=>{if(!snapshot?.onboarding.ready)return;run($('#submitReview'),'#reviewMessage',()=>api('/submit',{method:'POST',body:{}}),'SUBMITTED');});
    translatePage();loadOptions('types');loadOptions('companies');
    if(token){busy($('#accountSubmit'),true);message('#accountMessage',t('wait'));refresh().catch(error=>{if(token)message('#accountMessage',errorMessage(error),true);}).finally(()=>busy($('#accountSubmit'),false));}
  }
  return {mount,translate,words,escape,uploadProblem,accountPayload,createApiClient};
});
