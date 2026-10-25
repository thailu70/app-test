package com.example.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.R
import com.example.data.api.canonicalAccountPhone
import com.example.ui.screens.passenger.*
import com.example.ui.viewmodel.AppRole
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PasswordRecoveryScreen(model: MainViewModel, role: AppRole, language: AppLanguage,
    initialPhone: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf(initialPhone) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendAfter by remember { mutableLongStateOf(0L) }
    fun t(en: String, am: String, om: String) = authText(language, en, am, om)
    fun request() {
        if (canonicalAccountPhone(phone) == null) { error = t("Enter a valid mobile number.", "ትክክለኛ ስልክ ያስገቡ።", "Lakkoofsa bilbilaa sirrii galchi."); return }
        if (System.currentTimeMillis() < resendAfter) { error = t("Please wait a minute before requesting another code.", "ሌላ ኮድ ከመጠየቅዎ በፊት አንድ ደቂቃ ይጠብቁ።", "Koodii biraa gaafachuun dura daqiiqaa tokko eegi."); return }
        scope.launch {
            busy = true; error = null
            try {
                val response = model.requestAccountRecovery(role, phone)
                sent = true; message = response.message
                resendAfter = System.currentTimeMillis() + response.retryAfterSeconds.coerceIn(1, 900) * 1000L
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    BackHandler { if (!busy) onBack() }
    Box(modifier.fillMaxSize().background(CommuteCream).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        LazyColumn(Modifier.fillMaxSize().testTag("auth_recovery_screen"), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.ArrowBack, t("Back", "ተመለስ", "Deebi'i"), tint = CommuteInk) }
                    Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.width(130.dp).height(40.dp))
                }
                Text(t("Reset your password", "የይለፍ ቃልዎን ይቀይሩ", "Jecha iccitii kee haaromsi"), color = CommuteInk, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(t("A reset code confirms that you control the phone on your account. Registration phone verification remains optional.", "የመቀየሪያ ኮድ የመለያዎን ስልክ እንደሚቆጣጠሩ ያረጋግጣል። የምዝገባ ስልክ ማረጋገጥ አሁንም አማራጭ ነው።", "Koodiin haaromsaa bilbilli herrega kee kan kee ta'uu mirkaneessa. Mirkaneessi bilbilaa galmee amma iyyuu filannoo dha."), color = CommuteInk, fontSize = 13.sp)
            }
            if (!done) {
                item { AuthField(phone, { phone = it; sent = false; code = ""; resendAfter = 0; error = null }, t("Account mobile number", "የመለያ ስልክ ቁጥር", "Bilbila herregaa"), Modifier.testTag("auth_recovery_phone"), phone = true, enabled = !busy, language = language) }
                if (!sent) item { AuthPrimaryButton(t("Send reset code", "የመቀየሪያ ኮድ ላክ", "Koodii haaromsaa ergi"), ::request, enabled = !busy, busy = busy, modifier = Modifier.testTag("auth_recovery_request")) }
                else {
                    item { AuthField(code, { code = it.filter(Char::isDigit).take(6); error = null }, t("6-digit reset code", "ባለ 6 አሃዝ መቀየሪያ ኮድ", "Koodii haaromsaa dijiitii 6"), Modifier.testTag("auth_recovery_code"), enabled = !busy, language = language, leadingIcon = Icons.Default.Sms, decimal = true) }
                    item { AuthField(password, { password = it; error = null }, t("New password", "አዲስ የይለፍ ቃል", "Jecha iccitii haaraa"), Modifier.testTag("auth_recovery_password"), password = true, enabled = !busy, helper = t("Use at least 10 characters.", "ቢያንስ 10 ፊደላት ይጠቀሙ።", "Yoo xiqqaate qubee 10 fayyadami."), language = language) }
                    item { AuthField(confirmation, { confirmation = it; error = null }, t("Confirm new password", "አዲስ የይለፍ ቃል ያረጋግጡ", "Jecha iccitii haaraa mirkaneessi"), Modifier.testTag("auth_recovery_confirmation"), password = true, enabled = !busy, language = language) }
                    item {
                        AuthPrimaryButton(t("Update password", "የይለፍ ቃል ቀይር", "Jecha iccitii jijjiiri"), {
                            when {
                                code.length != 6 -> error = t("Enter the 6-digit reset code.", "ባለ 6 አሃዝ ኮድ ያስገቡ።", "Koodii dijiitii 6 galchi.")
                                password.length < 10 || password.toByteArray(Charsets.UTF_8).size > 72 -> error = t("Use a password with at least 10 characters and no more than 72 bytes.", "ቢያንስ 10 ፊደላት እና ከ72 ባይት ያልበለጠ የይለፍ ቃል ይጠቀሙ።", "Jecha iccitii yoo xiqqaate qubee 10 fi baayitii 72 gadi fayyadami.")
                                password != confirmation -> error = t("The passwords do not match.", "የይለፍ ቃሎቹ አይመሳሰሉም።", "Jechoonni iccitii wal hin fakkaatan.")
                                else -> scope.launch {
                                    busy = true; error = null
                                    try { message = model.resetAccountPassword(role, phone, code, password).message; done = true; password = ""; confirmation = ""; code = "" }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message }
                                    finally { busy = false }
                                }
                            }
                        }, enabled = !busy, busy = busy, modifier = Modifier.testTag("auth_recovery_reset"))
                        TextButton(onClick = ::request, enabled = !busy, modifier = Modifier.testTag("auth_recovery_resend")) { Text(t("Request another code", "ሌላ ኮድ ይጠይቁ", "Koodii biraa gaafadhu"), color = CommuteTeal) }
                    }
                }
            }
            item {
                message?.let { Text(it, color = CommuteTeal, modifier = Modifier.testTag("auth_recovery_message")) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("auth_recovery_error")) }
                if (done) AuthPrimaryButton(t("Return to sign in", "ወደ መግቢያ ተመለስ", "Gara seensaatti deebi'i"), onBack, modifier = Modifier.testTag("auth_recovery_done"))
                Text("info@passo.live", color = CommuteTeal)
            }
        }
    }
}
