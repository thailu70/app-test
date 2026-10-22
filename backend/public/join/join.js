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
  Object.assign(words, {
    routePreference:['Your daily commute route','የእርስዎ ዕለታዊ የጉዞ መስመር','Daandii imala guyyaa keessan'],routeChoice:['Choose how to apply','የማመልከቻ ዓይነት ይምረጡ','Akkaataa iyyachuu filadhaa'],existingRoute:['Choose an existing route','ያለ መስመር ይምረጡ','Daandii jiru filadhaa'],proposeRoute:['Propose a new route','አዲስ መስመር ያቅርቡ','Daandii haaraa yaada dhiheessaa'],activeRoute:['Active commute route','ንቁ ዕለታዊ የጉዞ መስመር','Daandii imala guyyaa hojiirra jiru'],chooseRoute:['Choose a route','መስመር ይምረጡ','Daandii filadhaa'],proposedDeparture:['Proposed departure','የታቀደ መነሻ','Kaʼumsa yaadame'],proposedDestination:['Proposed destination','የታቀደ መድረሻ','Gaʼumsa yaadame'],monthlyProposal:['Proposed monthly fee (ETB)','የቀረበ ወርሃዊ ክፍያ (ብር)','Kaffaltii jiʼaa yaadame (ETB)'],feeBasis:['Monthly fee is for','ወርሃዊ ክፍያው ለ','Kaffaltiin jiʼaa kan'],perPassenger:['One passenger','አንድ ተሳፋሪ','Imaltuu tokko'],perVehicle:['The whole vehicle','ሙሉ ተሽከርካሪው','Konkolaataa guutuu'],feeHelp:['Your proposal goes to the administrator for review. It does not change the published passenger fare.','ያቀረቡት ክፍያ ለአስተዳዳሪው ግምገማ ይላካል። የታተመውን የተሳፋሪ ዋጋ አይቀይርም።','Yaadni kaffaltii keessanii bulchaan akka ilaaluuf ergama. Gatii imaltootaa maxxanfame hin jijjiiru.'],feeInvalid:['Enter a monthly fee above 0 and up to 100,000 ETB, with no more than two decimal places.','ከ0 በላይ እስከ 100,000 ብር ወርሃዊ ክፍያ ያስገቡ፤ ከነጥብ በኋላ ከሁለት አሃዞች አይበልጥ።','Kaffaltii jiʼaa 0 ol hanga ETB 100,000 galchaa; tuqaa booda lakkoofsa lama hin caalin.'],noRoutes:['No active commute routes are available. Choose “Propose a new route” to apply with your departure and destination.','ንቁ ዕለታዊ መስመሮች አልተገኙም። “አዲስ መስመር ያቅርቡ” በመምረጥ መነሻና መድረሻዎን ያስገቡ።','Daandiin imala guyyaa hojiirra jiru hin argamne. “Daandii haaraa yaada dhiheessaa” filadhaatii kaʼumsaa fi gaʼumsa galchaa.'],routesFailed:['Routes could not load. Try again, or choose “Propose a new route”.','መስመሮችን መጫን አልተቻለም። እንደገና ይሞክሩ ወይም “አዲስ መስመር ያቅርቡ” ይምረጡ።','Daandiiwwan hin feʼamne. Irra deebiʼaa yookaan “Daandii haaraa yaada dhiheessaa” filadhaa.'],dailyCreateIntro:['Choose an active route or propose your departure and destination, then suggest a monthly fee. The administrator accepts your registration before phone verification and document upload.','ንቁ መስመር ይምረጡ ወይም መነሻና መድረሻ ያቅርቡ፣ ከዚያም ወርሃዊ ክፍያ ይጠቁሙ። ስልክ ከማረጋገጥና ሰነድ ከመስቀል በፊት አስተዳዳሪው ምዝገባዎን ይቀበላል።','Daandii jiru filadhaa yookaan kaʼumsaa fi gaʼumsa yaada dhiheessaa; kaffaltii jiʼaas ibsaa. Bilbila mirkaneessuufi sanadoota galchuu dura bulchaan galmee keessan ni fudhata.'],initialRegistration:['INITIAL REGISTRATION','የመጀመሪያ ምዝገባ','GALMEE JALQABAA'],sendRegistration:['Send registration for acceptance','ምዝገባውን ለቅበላ ላክ','Galmee akka fudhatamuuf ergi'],awaitingAcceptance:['Waiting for administrator acceptance','የአስተዳዳሪ ቅበላ በመጠባበቅ ላይ','Fudhatama bulchaa eegaa jira'],acceptanceWaitingBody:['Your registration has been sent for administrator review. Phone verification, document upload and the remaining details unlock only after acceptance. No passengers are allocated yet.','ምዝገባዎ ለአስተዳዳሪ ግምገማ ተልኳል። ስልክ ማረጋገጥ፣ ሰነድ መስቀል እና ቀሪ መረጃዎች ከቅበላ በኋላ ብቻ ይከፈታሉ። እስካሁን ተሳፋሪዎች አልተመደቡም።','Galmeen keessan bulchaan akka ilaaluuf ergameera. Bilbila mirkaneessuu, sanadoota galchuu fi odeeffannoo hafan fudhatama booda qofa banamu. Imaltoonni amma hin ramadamne.'],acceptanceAccepted:['Registration accepted','ምዝገባው ተቀብሏል','Galmeen fudhatameera'],acceptanceAcceptedBody:['The administrator accepted your initial registration. You can now verify your phone, complete your details and upload documents, then send them for final review.','አስተዳዳሪው የመጀመሪያ ምዝገባዎን ተቀብሏል። አሁን ስልክዎን ያረጋግጡ፣ መረጃዎን ያሟሉና ሰነዶችን ይስቀሉ፤ ከዚያ ለመጨረሻ ግምገማ ይላኩ።','Bulchaan galmee jalqabaa keessan fudhateera. Amma bilbila mirkaneessaa, odeeffannoo guutaa, sanadoota galchaa; achiis ilaalcha dhumaaf ergaa.'],acceptanceRejected:['Registration not accepted','ምዝገባው አልተቀበለም','Galmeen hin fudhatamne'],acceptanceRejectedBody:['Your initial registration was not accepted. Read the administrator’s note below or contact support. Phone verification and document upload remain locked.','የመጀመሪያ ምዝገባዎ አልተቀበለም። ከታች ያለውን የአስተዳዳሪ ማስታወሻ ያንብቡ ወይም ድጋፍ ያግኙ። ስልክ ማረጋገጥና ሰነድ መስቀል ተዘግተው ይቀጥላሉ።','Galmeen jalqabaa keessan hin fudhatamne. Yaada bulchaa armaan gadii dubbisaa yookaan deeggarsa qunnamaa. Mirkaneessi bilbilaafi galchi sanadaa cufamanii turu.'],completeRoutePreference:['Choose your route and monthly fee proposal to send your registration for acceptance.','ምዝገባዎን ለቅበላ ለመላክ መስመርዎን ይምረጡ እና ወርሃዊ ክፍያ ያቅርቡ።','Galmee keessan akka fudhatamuuf erguuf daandii filadhaa, kaffaltii jiʼaas dhiheessaa.'],ADMIN_ACCEPTANCE:['Administrator acceptance','የአስተዳዳሪ ቅበላ','Fudhatama bulchaa'],ROUTE_PREFERENCE:['Route and monthly fee proposal','መስመር እና የወርሃዊ ክፍያ ሃሳብ','Yaada daandiifi kaffaltii jiʼaa'],AWAITING_ACCEPTANCE:['Waiting for acceptance','ቅበላ በመጠባበቅ ላይ','Fudhatama eegaa jira'],dailyStepAccount:['Choose a route & register','መስመር ይምረጡና ይመዝገቡ','Daandii filadhaatii galmaaʼaa'],dailyStepAccountBody:['Propose your route and monthly fee.','መስመርዎን እና ወርሃዊ ክፍያ ያቅርቡ።','Daandiifi kaffaltii jiʼaa dhiheessaa.'],dailyStepDetails:['Wait for acceptance','ቅበላ ይጠብቁ','Fudhatama eegaa'],dailyStepDetailsBody:['The administrator reviews your registration first.','አስተዳዳሪው በመጀመሪያ ምዝገባዎን ይገመግማል።','Bulchaan dura galmee keessan ilaala.'],dailyStepReview:['Verify & complete','ያረጋግጡና ያሟሉ','Mirkaneessaatii xumuraa'],dailyStepReviewBody:['After acceptance, verify your phone and add your documents for final review.','ከቅበላ በኋላ ስልክዎን ያረጋግጡና ሰነዶችዎን ለመጨረሻ ግምገማ ያቅርቡ።','Fudhatama booda bilbila mirkaneessaatii sanadoota ilaalcha dhumaaf galchaa.'],privateDocuments:['Documents are private to authorized administrators or your company’s reviewers. After approval, your profile photo may also be shown to passengers assigned to you.','ሰነዶች ፈቃድ ላላቸው አስተዳዳሪዎች ወይም ለኩባንያዎ ገምጋሚዎች ብቻ ይታያሉ። ከማጽደቅ በኋላ የመለያ ፎቶዎ ለእርስዎ ለተመደቡ ተሳፋሪዎችም ሊታይ ይችላል።','Sanadoota bulchitoota yookaan ilaaltota dhaabbata keessanii hayyamamaniif qofa. Erga raggaʼee booda suuraan keessan imaltoota isiniif ramadamaniifis mulʼachuu dandaʼa.']
  });
  Object.assign(words,{
    monthlyProposal:['Proposed monthly fee for the whole vehicle (ETB)','ለሙሉ ተሽከርካሪው የቀረበ ወርሃዊ ክፍያ (ብር)','Kaffaltii jiʼaa konkolaataa guutuuf yaadame (ETB)'],
    feeInvalid:['Enter a whole-vehicle monthly fee above 0 and up to 1,000,000 ETB, with no more than two decimal places.','ለሙሉ ተሽከርካሪው ከ0 በላይ እስከ 1,000,000 ብር ወርሃዊ ክፍያ ያስገቡ፤ ከነጥብ በኋላ ከሁለት አሃዞች አይበልጥ።','Kaffaltii jiʼaa konkolaataa guutuuf 0 ol hanga ETB 1,000,000 galchaa; tuqaa booda lakkoofsa lama hin caalin.']
  });
  Object.assign(words,{
    dailyButton:['Daily commute','ዕለታዊ ጉዞ','Imala guyyaa'],dailyCaption:['My own vehicle','የራሴ ተሽከርካሪ','Konkolaataa dhuunfaa'],intercityButton:['Intercity','የከተማ መካከል ጉዞ','Magaalaa gidduu'],intercityCaption:['A company vehicle','የኩባንያ ተሽከርካሪ','Konkolaataa dhaabbataa'],departure:['Departure','መነሻ','Kaʼumsa'],destination:['Destination','መድረሻ','Gaʼumsa'],monthlyVehiclePrice:['Monthly price for the whole vehicle (ETB)','ለሙሉ ተሽከርካሪው ወርሃዊ ዋጋ (ብር)','Gatii jiʼaa konkolaataa guutuu (ETB)'],priceEnterEndpoints:['Enter departure, destination and vehicle type to check the monthly price.','ወርሃዊ ዋጋውን ለማየት መነሻ፣ መድረሻ እና የተሽከርካሪ ዓይነት ያስገቡ።','Gatii jiʼaa ilaaluuf kaʼumsa, gaʼumsaa fi gosa konkolaataa galchaa.'],priceChecking:['Checking the route and monthly price…','መስመሩ እና ወርሃዊ ዋጋው እየተፈተሹ ነው…','Daandiifi gatiin jiʼaa ilaalamaa jira…'],priceLookupError:['The current price could not be checked. Try again before sending your registration.','ወቅታዊ ዋጋውን ማረጋገጥ አልተቻለም። ምዝገባዎን ከመላክ በፊት እንደገና ይሞክሩ።','Gatiin ammaa hin mirkanoofne. Galmee erguu dura irra deebiʼaa.'],priceFixed:['Monthly vehicle price · set by the route and vehicle type','ወርሃዊ የተሽከርካሪ ዋጋ · በመስመሩ እና በተሽከርካሪው ዓይነት የተወሰነ','Gatii jiʼaa konkolaataa · daandiifi gosa konkolaataan murtaaʼe'],priceFormula:['{capacity} passenger seats × ETB {fare} per passenger / month = ETB {total} for the whole vehicle / month.','{capacity} የተሳፋሪ መቀመጫዎች × በወር ለአንድ ተሳፋሪ {fare} ብር = በወር ለሙሉ ተሽከርካሪው {total} ብር።','Teessoo imaltootaa {capacity} × ETB {fare} imaltuu tokkoof / jiʼa = ETB {total} konkolaataa guutuuf / jiʼa.'],priceManual:['No set monthly price is available for this route and vehicle type.','ለዚህ መስመር እና የተሽከርካሪ ዓይነት የተወሰነ ወርሃዊ ዋጋ የለም።','Daandiifi gosa konkolaataa kanaaf gatiin jiʼaa murtaaʼe hin jiru.'],priceAmbiguous:['More than one route matches these endpoints. Your price proposal will be reviewed.','እነዚህን መነሻና መድረሻ የሚያገናኙ ከአንድ በላይ መስመሮች አሉ። የዋጋ ሃሳብዎ ይገመገማል።','Kaʼumsaa fi gaʼumsa kanaan daandiin tokkoo ol jira. Yaadni gatii keessanii ni ilaalama.'],manualPriceHelp:['Propose a monthly fee for the whole vehicle for administrator review. This does not change the passenger fare.','ለአስተዳዳሪ ግምገማ ለሙሉ ተሽከርካሪው ወርሃዊ ክፍያ ያቅርቡ። ይህ የተሳፋሪ ዋጋውን አይቀይርም።','Bulchaan akka ilaaluuf kaffaltii jiʼaa konkolaataa guutuu dhiheessaa. Kun gatii imaltootaa hin jijjiiru.'],suggestionsError:['Suggestions could not load. You can still type your departure and destination.','ጥቆማዎችን መጫን አልተቻለም። መነሻና መድረሻዎን መጻፍ ይችላሉ።','Yaadawwan hin feʼamne. Kaʼumsaa fi gaʼumsa keessan barreessuu dandeessu.'],dailyCreateIntro:['Enter your departure and destination, then choose your vehicle type. We’ll show the monthly vehicle price when available. Administrator acceptance comes before phone verification and document upload.','መነሻና መድረሻዎን ያስገቡ፣ ከዚያም የተሽከርካሪውን ዓይነት ይምረጡ። ሲገኝ ወርሃዊ የተሽከርካሪ ዋጋውን እናሳያለን። ስልክ ከማረጋገጥና ሰነድ ከመስቀል በፊት የአስተዳዳሪ ቅበላ ያስፈልጋል።','Kaʼumsaa fi gaʼumsa galchaatii gosa konkolaataa filadhaa. Gatiin jiʼaa yoo jiraate ni mulʼisa. Mirkaneessa bilbilaafi galcha sanadaa dura fudhatamni bulchaa barbaachisa.'],completeRoutePreference:['Enter your departure and destination to complete your registration.','ምዝገባዎን ለማሟላት መነሻና መድረሻዎን ያስገቡ።','Galmee keessan guutuuf kaʼumsaa fi gaʼumsa galchaa.'],dailyStepAccount:['Enter your route & register','መስመርዎን ያስገቡና ይመዝገቡ','Daandii galchaatii galmaaʼaa'],dailyStepAccountBody:['Check the price for your vehicle and route.','ለተሽከርካሪዎና መስመርዎ ዋጋውን ይመልከቱ።','Gatii konkolaataafi daandii keessanii ilaalaa.']
  });
  const languageIndex = { en:0, am:1, om:2 };
  words.PROPOSED_MONTHLY_FEE=words.monthlyProposal;
  words.initialRegistrationIncomplete=['Complete your initial registration','የመጀመሪያ ምዝገባዎን ያሟሉ','Galmee jalqabaa keessan guutaa'];
  words.reviewRoutePrice=['Review route and price','መስመርና ዋጋ ይመልከቱ','Daandiifi gatii ilaalaa'];
  words.closeRouteEditor=['Close route details','የመስመር መረጃን ዝጋ','Odeeffannoo daandii cufi'];
  const translate = (key, language = 'en') => words[key]?.[languageIndex[language] ?? 0] || words[key]?.[0] || key;
  const escape = value => String(value ?? '').replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const uploadProblem = (file, kind) => !file || !Number.isFinite(file.size) || file.size <= 0 || file.size > 5*1024*1024 ? 'fileSize' : !(kind === 'PHOTO' ? ['image/jpeg','image/png'] : ['image/jpeg','image/png','application/pdf']).includes(file.type) ? kind === 'PHOTO' ? 'photoType' : 'fileType' : null;
  function accountPayload(role, mode, values, quote) {
    const clean=key=>String(values[key]||'').trim();
    const payload={role,phone:clean('phone'),password:values.password};
    if(mode!=='register')return payload;
    payload.fullName=clean('fullName');
    if(role==='COMPANY')payload.companyName=clean('companyName');
    else {
      payload.serviceType=values.serviceType;payload.licenseNumber=clean('licenseNumber');
      if(payload.serviceType==='INTERCITY')payload.companyId=clean('companyId');
      else {for(const key of ['assignedVehiclePlate','vehicleModel','vehicleType'])payload[key]=clean(key);Object.assign(payload,routePreferencePayload(values,quote));}
    }
    return payload;
  }
  function routePreferencePayload(values,quote) {
    const payload={proposedDeparture:String(values.proposedDeparture||'').trim(),proposedDestination:String(values.proposedDestination||'').trim(),vehicleType:String(values.vehicleType||'').trim()};
    if(quote?.price?.mode!=='FIXED'){payload.proposedMonthlyFeeEtb=Number(values.proposedMonthlyFeeEtb);payload.proposedMonthlyFeeBasis='PER_VEHICLE';}
    return payload;
  }
  function registrationFields(role,mode,service,choice) {
    const fields=['phone','password'];if(mode!=='register')return fields;
    fields.push('fullName');if(role==='COMPANY')return fields.concat('companyName');
    fields.push('serviceType','licenseNumber');if(service==='INTERCITY')return fields.concat('companyId');
    return fields.concat('assignedVehiclePlate','vehicleModel','vehicleType','proposedMonthlyFeeEtb','proposedDeparture','proposedDestination');
  }
  function applyRegistrationFields(controls,role,mode,service,choice) {
    const enabled=registrationFields(role,mode,service,choice);
    for(const control of controls)if(control.name){control.disabled=!enabled.includes(control.name);control.required=!control.disabled;}
  }
  function onboardingAccess(onboarding) {
    const locked=onboarding?.acceptanceRequired===true&&onboarding.canVerifyAndUpload!==true;
    return {locked,canSubmit:!locked&&onboarding?.ready===true&&!['SUBMITTED','APPROVED'].includes(onboarding.reviewStatus)};
  }
  function applyOnboardingAccess(sections,onboarding) {
    const {locked}=onboardingAccess(onboarding);
    for(const section of sections){section.hidden=locked;if(locked)for(const input of section.querySelectorAll('input,select,button'))input.disabled=true;}
    return locked;
  }
  function validMonthlyFee(value) {const amount=Number(value);return Number.isFinite(amount)&&amount>0&&amount<=1000000&&Math.abs(amount*100-Math.round(amount*100))<0.000001;}
  function quoteAccess(state){const canSubmit=state?.status==='READY',fixed=canSubmit&&state.quote?.price?.mode==='FIXED';return {canSubmit,readOnly:!canSubmit||fixed,fixedAmount:fixed?Number(state.quote.price.amountEtb):null};}
  function createCommuteQuoteController({fetchFn,onChange=()=>{},delayMs=300,setTimer=setTimeout,clearTimer=clearTimeout}) {
    let generation=0,timer=null,request=null,state={status:'INCOMPLETE',key:'',context:{},quote:null};
    const publish=value=>{state=Object.freeze(value);onChange(state);return state;};
    function reset(){generation++;if(timer!==null)clearTimer(timer);timer=null;request?.abort();request=null;if(state.key||state.status!=='INCOMPLETE')publish({status:'INCOMPLETE',key:'',context:{},quote:null});}
    function update(values,{immediate=false,force=false}={}) {
      const context={departure:String(values.departure||'').trim(),destination:String(values.destination||'').trim(),vehicleType:String(values.vehicleType||'').trim()};
      const key=JSON.stringify(context);if(!force&&key===state.key)return Promise.resolve(state);
      const current=++generation;if(timer!==null)clearTimer(timer);timer=null;request?.abort();request=null;
      if(context.departure.length<2||context.destination.length<2||!context.vehicleType)return Promise.resolve(publish({status:'INCOMPLETE',key,context,quote:null}));
      publish({status:'LOADING',key,context,quote:null});
      async function lookup(){
        if(current!==generation)return state;
        const controller=new AbortController();request=controller;const timeout=setTimeout(()=>controller.abort(),15000);
        try {
          const response=await fetchFn('/api/onboarding/commute-quote?'+new URLSearchParams(context),{method:'GET',credentials:'omit',redirect:'error',signal:controller.signal});
          const quote=await response.json();if(current!==generation)return state;
          const price=quote.price,match=quote.routeMatch;
          if(!response.ok||quote.success!==true||!['EXACT','NONE','AMBIGUOUS'].includes(match?.status)||!['FIXED','PROPOSED'].includes(price?.mode)||price.currency!=='ETB'||price.basis!=='PER_VEHICLE')throw new Error('Invalid quote response.');
          if(price.mode==='FIXED'&&(match.status!=='EXACT'||!validMonthlyFee(price.amountEtb)||price.source!=='ROUTE_PASSENGER_FARE_X_CAPACITY'||!Number.isInteger(price.passengerCapacity)||price.passengerCapacity<1||!Number.isFinite(Number(price.passengerMonthlyFareEtb))||Number(price.passengerMonthlyFareEtb)<=0))throw new Error('Invalid fixed price.');
          return publish({status:'READY',key,context,quote});
        }catch(_){if(current===generation)return publish({status:'ERROR',key,context,quote:null});return state;}
        finally{clearTimeout(timeout);if(request===controller)request=null;}
      }
      if(immediate)return lookup();timer=setTimer(()=>{timer=null;lookup();},delayMs);return Promise.resolve(state);
    }
    return {update,reset,dispose:reset,getState:()=>state};
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
    const files=new Map(), catalog={types:[],companies:[]},quotes={},priceKeys={},suggestionTasks=new Map();
    let accountBusy=false,preferenceBusy=false,editingPreference=false;
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
      quotes.registration?.reset();quotes.pending?.reset();priceKeys.registration='';priceKeys.pending='';accountBusy=false;preferenceBusy=false;editingPreference=false;
      $('#accountForm').reset(); $('#profileForm').reset(); $('#otpForm').reset();$('#routePreferenceForm').reset();delete $('#routePreferenceForm').dataset.accountId; $('#password').type='password';
      all('#dashboard button').forEach(button=>{button.disabled=false;button.setAttribute('aria-busy','false');});
      $('#requestOtp').textContent=t('sendCode');
      $('#entry').hidden=false; $('#dashboard').hidden=true; $('#logout').hidden=true; mode='login';
      $('#accountSubmit').disabled=false;
      for(const id of ['#accountMessage','#otpMessage','#profileMessage','#documentMessage','#dashboardMessage','#reviewMessage','#preferenceMessage'])message(id,'');
      $('#documentCards').replaceChildren();delete $('#documentCards').dataset.renderKey; updateAuth(); if(expired)message('#accountMessage',t('expired'),true);
    }
    const api=createApiClient({fetchFn:window.fetch.bind(window),getToken:()=>token,onUnauthorized:()=>signOut(true)});
    function busy(element,value) { element.disabled=value; element.setAttribute('aria-busy',String(value)); }
    const priceControls={registration:{departure:'#proposedDeparture',destination:'#proposedDestination',vehicle:'#vehicleType',fee:'#proposedMonthlyFeeEtb',status:'#routeLookupStatus',help:'#registrationPriceHelp',retry:'#retryRoutes'},pending:{departure:'#pendingDeparture',destination:'#pendingDestination',vehicle:'#pendingVehicleType',fee:'#pendingFee',status:'#pendingLookupStatus',help:'#pendingPriceHelp',retry:'#retryPendingPrice'}};
    function quoteValues(name){const c=priceControls[name];return {departure:$(c.departure).value,destination:$(c.destination).value,vehicleType:$(c.vehicle).value};}
    function updateSubmitAvailability(){const needsPrice=role==='DRIVER'&&mode==='register'&&$('#serviceType').value==='DAILY_COMMUTE';$('#accountSubmit').disabled=accountBusy||(needsPrice&&!quoteAccess(quotes.registration?.getState()).canSubmit);$('#saveRoutePreference').disabled=preferenceBusy||$('#routePreferenceForm').hidden||!quoteAccess(quotes.pending?.getState()).canSubmit;}
    function renderPrice(name,state){
      const c=priceControls[name],field=$(c.fee),price=state.quote?.price,fixed=state.status==='READY'&&price?.mode==='FIXED';
      if(priceKeys[name]!==state.key){field.value='';priceKeys[name]=state.key;}
      field.readOnly=quoteAccess(state).readOnly;field.dataset.priceMode=fixed?'FIXED':'PROPOSED';
      $(c.retry).hidden=state.status!=='ERROR';$(c.status).classList.toggle('error',state.status==='ERROR');
      if(fixed){field.value=String(price.amountEtb);message(c.status,t('priceFixed'));const numbers={capacity:price.passengerCapacity,fare:Number(price.passengerMonthlyFareEtb).toLocaleString('en-GB',{maximumFractionDigits:2}),total:Number(price.amountEtb).toLocaleString('en-GB',{maximumFractionDigits:2})};$(c.help).textContent=t('priceFormula').replace(/\{(\w+)\}/g,(_match,key)=>numbers[key]);}
      else if(state.status==='READY'){message(c.status,t(state.quote.routeMatch.status==='AMBIGUOUS'?'priceAmbiguous':'priceManual'));$(c.help).textContent=t('manualPriceHelp');}
      else{message(c.status,t(state.status==='LOADING'?'priceChecking':state.status==='ERROR'?'priceLookupError':'priceEnterEndpoints'),state.status==='ERROR');$(c.help).textContent='';}
      updateSubmitAvailability();
    }
    function updateQuote(name,options){return quotes[name].update(quoteValues(name),options);}
    function scheduleSuggestions(name,field,immediate=false){
      const c=priceControls[name],input=field==='departure'?c.departure:c.destination,key=name+field,previous=suggestionTasks.get(key);if(previous){clearTimeout(previous.timer);previous.controller?.abort();}
      const task={timer:null,controller:null};suggestionTasks.set(key,task);
      async function load(){
        const controller=new AbortController();task.controller=controller;const timeout=setTimeout(()=>controller.abort(),15000);
        try{const query=new URLSearchParams({field,q:$(input).value.trim(),...(field==='destination'?{departure:$(c.departure).value.trim()}:{})});const response=await window.fetch('/api/onboarding/commute-suggestions?'+query,{credentials:'omit',redirect:'error',signal:controller.signal});const result=await response.json();if(suggestionTasks.get(key)!==task)return;if(!response.ok||!Array.isArray(result.places))throw new Error('Unavailable suggestions.');$('#'+$(input).getAttribute('list')).innerHTML=result.places.map(place=>'<option value="'+escape(place.label)+'"></option>').join('');message(name==='pending'?'#pendingSuggestionStatus':'#routeSuggestionStatus','');}
        catch(_){if(suggestionTasks.get(key)===task)message(name==='pending'?'#pendingSuggestionStatus':'#routeSuggestionStatus',t('suggestionsError'));}finally{clearTimeout(timeout);}
      }
      if(immediate)load();else task.timer=setTimeout(load,250);
    }
    function translatePage() {
      doc.documentElement.lang=language;
      all('[data-i18n]').forEach(el=>{ el.textContent=t(el.dataset.i18n); });
      all('[data-language]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.language===language)));
      updateAuth(); renderOptions(); if(snapshot)renderDashboard(false);for(const name of Object.keys(quotes))renderPrice(name,quotes[name].getState());
    }
    function updateAuth() {
      all('[data-role]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.role===role)));
      const registration=mode==='register', driver=role==='DRIVER', daily=$('#serviceType').value==='DAILY_COMMUTE';
      all('[data-registration]').forEach(el=>{ el.hidden=!registration || (el.hasAttribute('data-company')&&driver) || (el.hasAttribute('data-driver')&&!driver) || (el.hasAttribute('data-daily')&&!daily) || (el.hasAttribute('data-intercity')&&daily); });
      all('[data-service]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.service===$('#serviceType').value)));
      applyRegistrationFields($('#accountForm').elements,role,mode,$('#serviceType').value);
      $('#registerTab').setAttribute('aria-pressed',String(registration));$('#loginTab').setAttribute('aria-pressed',String(!registration));
      $('#formTitle').textContent=t(registration?'createTitle':'loginTitle'); $('#formIntro').textContent=t(registration?(driver&&daily?'dailyCreateIntro':'createIntro'):'loginIntro');
      $('#fullNameLabel').textContent=t(driver?'fullName':'representative'); $('#accountSubmit span').textContent=t(registration?(driver&&daily?'sendRegistration':'createButton'):'loginButton');
      $('#password').minLength=registration?10:1; $('#password').autocomplete=registration?'new-password':'current-password';
      $('#showPassword').textContent=t($('#password').type==='password'?'show':'hide');
      for(const key of ['stepAccount','stepAccountBody','stepDetails','stepDetailsBody','stepReview','stepReviewBody'])$('[data-i18n="'+key+'"]').textContent=t(driver&&daily?'daily'+key[0].toUpperCase()+key.slice(1):key);
      if(quotes.registration){if(registration&&driver&&daily)updateQuote('registration');else quotes.registration.reset();}updateSubmitAvailability();
    }
    function renderOptions() {
      for (const [selector,rows,placeholder] of [['#vehicleType',catalog.types,'chooseType'],['#driverCompany',catalog.companies,'chooseCompany']]) {
        const select=$(selector), value=select.value;
        select.innerHTML='<option value="">'+escape(t(placeholder))+'</option>'+rows.map(row=>'<option value="'+escape(row.code||row.id)+'">'+escape(language==='am'&&row.nameAm?row.nameAm:row.name)+(row.capacity?' · '+escape(row.capacity)+' '+escape(t('seats')):'')+'</option>').join('');
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
        catalog[kind]=rows.filter(row=>types?row.active!==false:!row.status||row.status==='ACTIVE'); renderOptions();updateAuth();
        message(status,catalog[kind].length?'':t(types?'noTypes':'noCompanies'));$(retry).hidden=catalog[kind].length>0;
      } catch (_) { message(status,t('networkError'),true);$(retry).hidden=false; }
    }
    function updatePendingPreference() {
      const form=$('#routePreferenceForm');
      for(const control of form.elements)if(control.name){control.disabled=form.hidden;control.required=!form.hidden;}
      if(quotes.pending){if(form.hidden)quotes.pending.reset();else updateQuote('pending');}updateSubmitAvailability();
    }
    async function refresh(fill=true) {
      const current=revision;const result=await api('/me');if(current!==revision)return;
      if(!result.account||!result.onboarding)throw new Error('Invalid application response.');snapshot=result;role=result.account.role;renderDashboard(fill);
    }
    function renderDashboard(fill=true) {
      if(!snapshot)return;
      const {account,onboarding:o}=snapshot, profile=o.profile||{}, status=o.reviewStatus||'DRAFT';
      const access=onboardingAccess(o),preference=o.routePreference||{};
      $('#entry').hidden=true;$('#dashboard').hidden=false;$('#logout').hidden=false;
      $('#dashboardTitle').textContent=account.fullName||account.companyName||t(role==='DRIVER'?'driver':'company');
      $('#accountIdentity').textContent=[t(role==='DRIVER'?'driver':'company'),account.companyName,account.phone].filter(Boolean).join(' · ');
      $('#reviewBadge').textContent=t(status);$('#progressTitle').textContent=status==='APPROVED'?t('APPROVED'):status==='SUBMITTED'?t('SUBMITTED'):t('progressTitle');
      $('#progressBody').textContent=status==='APPROVED'?t('approvalBody'):status==='SUBMITTED'?t('reviewPending'):t('progressBody');
      $('#acceptancePanel').hidden=o.acceptanceRequired!==true;
      const rejected=o.acceptanceStatus==='REJECTED';
      $('#acceptanceBadge').textContent=t(access.locked?(rejected?'acceptanceRejected':'AWAITING_ACCEPTANCE'):'acceptanceAccepted');
      $('#acceptanceBadge').classList.toggle('warning',access.locked);$('#acceptanceBadge').classList.toggle('error',rejected);
      $('#acceptanceMessage').textContent=t(access.locked?(rejected?'acceptanceRejectedBody':'acceptanceWaitingBody'):'acceptanceAcceptedBody')+(access.locked&&o.reviewNote?' '+o.reviewNote:'');
      const selectedRoute=preference.mode==='EXISTING'?(preference.routeName||t('existingRoute')):preference.mode==='PROPOSED'?[preference.departure,preference.destination].filter(Boolean).join(' → '):'';
      $('#routePreferenceSummary').innerHTML=(selectedRoute?'<div><dt>'+escape(t('routePreference'))+'</dt><dd>'+escape(selectedRoute)+'</dd></div>':'')+(validMonthlyFee(preference.proposedMonthlyFeeEtb)?'<div><dt>'+escape(t(preference.feeLocked?'monthlyVehiclePrice':'monthlyProposal'))+'</dt><dd>ETB '+escape(Number(preference.proposedMonthlyFeeEtb).toLocaleString('en-GB',{maximumFractionDigits:2}))+'</dd></div>':'');
      $('#savedPriceHelp').textContent=preference.feeLocked?t('priceFormula').replace(/\{(\w+)\}/g,(_match,key)=>({capacity:preference.passengerCapacity,fare:Number(preference.passengerMonthlyFareEtb).toLocaleString('en-GB',{maximumFractionDigits:2}),total:Number(preference.proposedMonthlyFeeEtb).toLocaleString('en-GB',{maximumFractionDigits:2})}[key])):'';
      const needsPreference=access.locked&&((o.initialMissing||[]).length>0||!preference.mode||!validMonthlyFee(preference.proposedMonthlyFeeEtb)||(o.missing||[]).includes('ROUTE_PREFERENCE'));
      if($('#routePreferenceForm').dataset.accountId!==account.id){$('#pendingDeparture').value=preference.departure||'';$('#pendingDestination').value=preference.destination||'';$('#pendingFee').value=preference.proposedMonthlyFeeEtb||'';$('#routePreferenceForm').dataset.accountId=account.id;}
      $('#pendingVehicleType').value=account.vehicleType||'';
      $('#pendingVehicleSummary').textContent=t('vehicleType')+': '+(catalog.types.find(type=>type.code===account.vehicleType)?.name||account.vehicleType||'—');
      $('#editRoutePreference').hidden=o.acceptanceRequired!==true;$('#editRoutePreference').textContent=t(editingPreference?'closeRouteEditor':'reviewRoutePrice');$('#editRoutePreference').setAttribute('aria-expanded',String(editingPreference||needsPreference));
      $('#routePreferenceForm').hidden=!(needsPreference||editingPreference);updatePendingPreference();
      $('#resubmitRegistration').hidden=!(access.locked&&rejected&&o.canInitiallySubmit===true);
      $('#resubmitRegistration').disabled=$('#resubmitRegistration').hidden;
      if(access.locked){$('#reviewBadge').textContent=t(rejected?'acceptanceRejected':'AWAITING_ACCEPTANCE');$('#progressTitle').textContent=t(rejected?'acceptanceRejected':'awaitingAcceptance');$('#progressBody').textContent=t(rejected?'acceptanceRejectedBody':'acceptanceWaitingBody');}
      if(needsPreference){$('#acceptanceBadge').textContent=t('DRAFT');$('#acceptanceMessage').textContent=t('completeRoutePreference');$('#reviewBadge').textContent=t('DRAFT');$('#progressTitle').textContent=t('initialRegistrationIncomplete');$('#progressBody').textContent=t('completeRoutePreference');}
      $('#progressCount').hidden=access.locked;
      const missing=Array.isArray(o.missing)?o.missing:[], documents=o.documents||[], required=o.requiredDocuments||[];
      const complete={phone:o.phoneVerified===true,profile:!missing.some(key=>['EMERGENCY_CONTACT_NAME','EMERGENCY_CONTACT_PHONE','VEHICLE_COLOR'].includes(key)),documents:required.length>0&&required.every(kind=>documents.some(item=>item.kind===kind)),submitted:Boolean(o.submittedAt)||status==='APPROVED'};
      $('#progressCount').textContent=Object.values(complete).filter(Boolean).length+' / 4';
      all('[data-check]').forEach(icon=>icon.closest('a').classList.toggle('done',complete[icon.dataset.check]));
      $('#phoneBadge').textContent=t(complete.phone?'verified':'notVerified');$('#phoneBadge').classList.toggle('warning',!complete.phone);
      $('#phoneSummary').textContent=complete.phone?(account.phone+' · '+t('verified')):t('phoneInstruction')+' '+account.phone;
      $('#otpForm').hidden=complete.phone;
      all('#phoneStep input,#phoneStep button').forEach(control=>control.disabled=access.locked);
      if(Date.now()<otpUntil)$('#requestOtp').disabled=true;
      const driver=role==='DRIVER';all('[data-driver-profile]').forEach(el=>{el.hidden=!driver;el.querySelector('input').disabled=!driver||access.locked;el.querySelector('input').required=driver&&!access.locked;});
      all('[data-company-profile]').forEach(el=>{el.hidden=driver;el.querySelector('input').disabled=driver;});
      $('#saveProfile').disabled=access.locked;
      if(fill)for(const key of ['emergencyContactName','emergencyContactPhone','vehicleColor','address','registrationNumber'])$('#'+key).value=profile[key]||'';
      const profileKey=JSON.stringify({role,language,documents,required,locked:access.locked});
      if(access.locked)files.clear();
      if($('#documentCards').dataset.renderKey!==profileKey){$('#documentCards').dataset.renderKey=profileKey;renderDocuments(access.locked?[]:required,documents);}
      $('#reviewSummary').textContent=status==='APPROVED'?t('approvalBody'):status==='SUBMITTED'?t('reviewPending'):o.ready?t('reviewReady'):t('reviewIncomplete');
      $('#missingItems').innerHTML=missing.map(key=>'<li>'+escape(t(key))+'</li>').join('');
      $('#submitReview').disabled=!access.canSubmit;
      $('#submitReview span').textContent=t(status==='SUBMITTED'?'SUBMITTED':status==='APPROVED'?'APPROVED':'submitReview');
      if(status==='REJECTED'&&o.reviewNote)message('#reviewMessage',o.reviewNote,true);else message('#reviewMessage','');
      applyOnboardingAccess([$('.checklist-nav'),$('#phoneStep'),$('#profileStep'),$('#documentsStep'),$('#reviewStep')],o);
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
    for(const name of ['registration','pending'])quotes[name]=createCommuteQuoteController({fetchFn:window.fetch.bind(window),onChange:state=>renderPrice(name,state)});
    all('[data-language]').forEach(button=>button.addEventListener('click',()=>{language=button.dataset.language;try{window.sessionStorage.setItem('passo.join.language',language);}catch(_){}translatePage();}));
    all('[data-role]').forEach(button=>button.addEventListener('click',()=>{role=button.dataset.role;message('#accountMessage','');updateAuth();}));
    $('#serviceType').addEventListener('change',updateAuth);
    all('[data-service]').forEach(button=>button.addEventListener('click',()=>{$('#serviceType').value=button.dataset.service;updateAuth();}));
    for(const name of ['registration','pending'])for(const field of ['departure','destination']){
      const input=$(priceControls[name][field]);input.addEventListener('input',()=>{updateQuote(name);scheduleSuggestions(name,field);if(field==='departure')scheduleSuggestions(name,'destination');});input.addEventListener('focus',()=>scheduleSuggestions(name,field,true));
    }
    $('#vehicleType').addEventListener('change',()=>updateQuote('registration'));
    $('#registerTab').addEventListener('click',()=>{mode='register';message('#accountMessage','');updateAuth();});
    $('#loginTab').addEventListener('click',()=>{mode='login';message('#accountMessage','');updateAuth();});
    $('#showPassword').addEventListener('click',()=>{const show=$('#password').type==='password';$('#password').type=show?'text':'password';$('#showPassword').setAttribute('aria-pressed',String(show));$('#showPassword').textContent=t(show?'hide':'show');});
    $('#retryTypes').addEventListener('click',()=>loadOptions('types'));$('#retryCompanies').addEventListener('click',()=>loadOptions('companies'));$('#retryRoutes').addEventListener('click',()=>updateQuote('registration',{force:true,immediate:true}));$('#retryPendingPrice').addEventListener('click',()=>updateQuote('pending',{force:true,immediate:true}));
    $('#accountForm').addEventListener('submit',async event=>{
      event.preventDefault();const form=event.currentTarget;if(!form.reportValidity())return;
      const daily=mode==='register'&&role==='DRIVER'&&$('#serviceType').value==='DAILY_COMMUTE',quote=quotes.registration.getState();
      if(daily&&quote.status!=='READY'){message('#accountMessage',t('priceLookupError'),true);return;}
      const payload=accountPayload(role,mode,Object.fromEntries([...form.elements].filter(input=>input.name).map(input=>[input.name,input.value])),quote.quote);
      if(daily&&quote.quote.price.mode==='PROPOSED'&&!validMonthlyFee(payload.proposedMonthlyFeeEtb)){message('#accountMessage',t('feeInvalid'),true);return;}
      const current=revision;accountBusy=true;busy($('#accountSubmit'),true);message('#accountMessage','');
      try {const result=await api(mode==='register'?'/register':'/login',{method:'POST',body:payload,anonymous:true});if(current!==revision)return;
        if(!result.token||!result.account||!result.onboarding)throw new Error('Invalid application response.');setToken(result.token);snapshot=result;role=result.account.role;$('#password').value='';$('#password').type='password';renderDashboard();$('#dashboardTitle').tabIndex=-1;$('#dashboardTitle').focus();}
      catch(error){if(current===revision)message('#accountMessage',errorMessage(error),true);}
      finally{accountBusy=false;busy($('#accountSubmit'),false);updateSubmitAvailability();payload.password='';}
    });
    $('#logout').addEventListener('click',()=>signOut());
    $('#refreshDashboard').addEventListener('click',()=>run($('#refreshDashboard'),'#dashboardMessage',()=>api('/me')));
    $('#editRoutePreference').addEventListener('click',()=>{editingPreference=!editingPreference;renderDashboard(false);if(editingPreference)$('#pendingDeparture').focus();});
    $('#profileForm').addEventListener('submit',event=>{event.preventDefault();if(onboardingAccess(snapshot?.onboarding).locked||!event.currentTarget.reportValidity())return;const keys=role==='DRIVER'?['emergencyContactName','emergencyContactPhone','vehicleColor']:['address','registrationNumber'];const body=Object.fromEntries(keys.map(key=>[key,$('#'+key).value.trim()]));run($('#saveProfile'),'#profileMessage',()=>api('/profile',{method:'PATCH',body}),'saved');});
    $('#routePreferenceForm').addEventListener('submit',async event=>{event.preventDefault();const form=event.currentTarget,quote=quotes.pending.getState();if(form.hidden||!form.reportValidity())return;if(quote.status!=='READY'){message('#preferenceMessage',t('priceLookupError'),true);return;}const body=routePreferencePayload(Object.fromEntries([...form.elements].filter(input=>input.name).map(input=>[input.name,input.value])),quote.quote);if(quote.quote.price.mode==='PROPOSED'&&!validMonthlyFee(body.proposedMonthlyFeeEtb)){message('#preferenceMessage',t('feeInvalid'),true);return;}preferenceBusy=true;try{await run($('#saveRoutePreference'),'#preferenceMessage',async()=>{const result=await api('/profile',{method:'PATCH',body});editingPreference=false;return result;});}finally{preferenceBusy=false;updateSubmitAvailability();}});
    $('#resubmitRegistration').addEventListener('click',()=>{const o=snapshot?.onboarding;if(!o?.canInitiallySubmit||o.acceptanceStatus!=='REJECTED')return;run($('#resubmitRegistration'),'#preferenceMessage',()=>api('/submit',{method:'POST',body:{}}),'AWAITING_ACCEPTANCE');});
    $('#requestOtp').addEventListener('click',async()=>{
      if(onboardingAccess(snapshot?.onboarding).locked)return;
      const current=revision;busy($('#requestOtp'),true);message('#otpMessage','');
      try {const result=await api('/request-otp',{method:'POST',body:{}});if(current!==revision)return;
        const delivered=result.delivery==='SENT'||result.sent===true;
        message('#otpMessage',delivered?t('codeSent'):t('codeNotSent'),!delivered);
        if(delivered){otpUntil=Date.now()+Math.max(1,Number(result.retryAfterSeconds)||60)*1000;clearInterval(otpTimer);otpTimer=setInterval(()=>{if(Date.now()>=otpUntil){clearInterval(otpTimer);otpTimer=null;$('#requestOtp').disabled=false;$('#requestOtp').textContent=t('sendCode');}else $('#requestOtp').textContent=Math.ceil((otpUntil-Date.now())/1000)+'s';},1000);}
      }catch(error){if(current===revision)message('#otpMessage',errorMessage(error),true);}finally{if(current===revision&&Date.now()>=otpUntil)busy($('#requestOtp'),false);}
    });
    $('#otpForm').addEventListener('submit',event=>{event.preventDefault();if(onboardingAccess(snapshot?.onboarding).locked||!event.currentTarget.reportValidity())return;run($('#verifyOtp'),'#otpMessage',()=>api('/verify-otp',{method:'POST',body:{code:$('#otpCode').value.trim()}}),'verified');});
    $('#documentCards').addEventListener('change',event=>{
      const kind=event.target.dataset.file;if(!kind||onboardingAccess(snapshot?.onboarding).locked)return;const file=event.target.files?.[0],card=event.target.closest('[data-document]'),problem=uploadProblem(file,kind),status=card.querySelector('.doc-message');
      status.textContent=problem?t(problem):'';status.classList.toggle('error',Boolean(problem));
      if(problem){files.delete(kind);event.target.value='';}else files.set(kind,file);
      card.querySelector('[data-filename]').textContent=problem?'':file.name;card.querySelector('[data-upload]').disabled=Boolean(problem);
    });
    $('#documentCards').addEventListener('click',async event=>{
      const button=event.target.closest('button');if(!button||onboardingAccess(snapshot?.onboarding).locked)return;const kind=button.dataset.upload||button.dataset.download;if(!kind)return;
      const current=revision,card=button.closest('[data-document]'),status=card.querySelector('.doc-message');busy(button,true);status.textContent='';
      try {
        if(button.dataset.upload){const file=files.get(kind),problem=uploadProblem(file,kind);if(problem)throw new Error(t(problem));const result=await api('/documents/'+kind,{method:'PUT',raw:file});if(current!==revision)return;files.delete(kind);if(result.account&&result.onboarding){snapshot=result;renderDashboard(false);}else await refresh(false);}
        else {const blob=await api('/documents/'+kind,{blob:true});if(current!==revision)return;const url=window.URL.createObjectURL(blob),link=doc.createElement('a');link.href=url;link.download='Passo-'+kind.toLowerCase()+'.'+(blob.type==='application/pdf'?'pdf':blob.type==='image/png'?'png':'jpg');doc.body.append(link);link.click();link.remove();setTimeout(()=>window.URL.revokeObjectURL(url),1000);}
      }catch(error){if(current===revision){status.textContent=error.server||error.network?errorMessage(error):error.message;status.classList.add('error');}}finally{if(current===revision)busy(button,false);}
    });
    $('#submitReview').addEventListener('click',()=>{if(!onboardingAccess(snapshot?.onboarding).canSubmit)return;run($('#submitReview'),'#reviewMessage',()=>api('/submit',{method:'POST',body:{}}),'SUBMITTED');});
    translatePage();loadOptions('types');loadOptions('companies');
    if(token){busy($('#accountSubmit'),true);message('#accountMessage',t('wait'));refresh().catch(error=>{if(token)message('#accountMessage',errorMessage(error),true);}).finally(()=>busy($('#accountSubmit'),false));}
  }
  return {mount,translate,words,escape,uploadProblem,accountPayload,routePreferencePayload,registrationFields,applyRegistrationFields,onboardingAccess,applyOnboardingAccess,validMonthlyFee,quoteAccess,createCommuteQuoteController,createApiClient};
});
