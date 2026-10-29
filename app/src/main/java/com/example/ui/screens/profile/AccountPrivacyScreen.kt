package com.example.ui.screens.profile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.Response

private val PrivacyCream = Color(0xFFFFFBF4)
private val PrivacyNavy = Color(0xFF092438)
private val PrivacyTeal = Color(0xFF007D75)

internal fun deletionSuccessMessage(status: String, language: AppLanguage): String = when (language) {
    AppLanguage.AMHARIC -> if (status == "PENDING_CLEANUP") "መለያዎ ተዘግቷል እና ከመለያው ወጥተዋል። የግል ፋይሎች መሰረዝ ገና አልተጠናቀቀም። በግላዊነት ፖሊሲው የተገለጹ አስፈላጊ መዝገቦች ይቀመጣሉ።" else "መለያዎ ተዘግቷል እና ከመለያው ወጥተዋል። በግላዊነት ፖሊሲው የተገለጹ አስፈላጊ መዝገቦች ይቀመጣሉ።"
    AppLanguage.OROMO -> if (status == "PENDING_CLEANUP") "Herregni kee cufamee keessaa baateetta. Faayiloota dhuunfaa haquun ammallee hin xumuramne. Galmeewwan barbaachisoo imaammata iccitii keessatti ibsaman ni hafu." else "Herregni kee cufamee keessaa baateetta. Galmeewwan barbaachisoo imaammata iccitii keessatti ibsaman ni hafu."
    else -> if (status == "PENDING_CLEANUP") "Your account is closed and you have been signed out. Private-file cleanup is still pending. Necessary records described in the privacy policy are retained." else "Your account is closed and you have been signed out. Necessary records described in the privacy policy are retained."
}

private fun deletionError(response: Response<*>, fallback: String): String =
    runCatching { JSONObject(response.errorBody()?.string().orEmpty()).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

@Composable
fun AccountPrivacyScreen(
    language: AppLanguage,
    onBack: () -> Unit,
    onClosed: (String) -> Unit,
    providedApi: AccountPrivacyApi? = null
) {
    val context = LocalContext.current
    val api = remember(providedApi) { providedApi ?: ApiClient.createFeatureService(AccountPrivacyApi::class.java) }
    val scope = rememberCoroutineScope()
    var eligibility by remember { mutableStateOf<AccountDeletionEligibility?>(null) }
    var loading by remember { mutableStateOf(true) }
    var deleting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var confirmDialog by remember { mutableStateOf(false) }
    fun t(en: String, am: String, om: String) = when (language) { AppLanguage.AMHARIC -> am; AppLanguage.OROMO -> om; else -> en }
    val genericError = t("Could not complete this request. Check your connection and try again.", "ጥያቄው አልተጠናቀቀም። ግንኙነትዎን ያረጋግጡ እና ደግመው ይሞክሩ።", "Gaaffiin kun hin xumuramne. Walqunnamtii kee ilaaliitii irra deebi'i.")
    BackHandler(enabled = deleting) { /* Do not abandon an account closure with an unknown outcome. */ }
    DisposableEffect(Unit) { onDispose { password = ""; confirmation = "" } }
    LaunchedEffect(api, refresh) {
        loading = true
        error = null
        eligibility = null
        try {
            val response = api.deletionEligibility()
            val body = response.body()
            check(response.isSuccessful && body?.success == true && body.deletion != null) { deletionError(response, body?.error ?: genericError) }
            eligibility = body!!.deletion
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IllegalStateException) { error = failure.message ?: genericError }
        catch (_: Exception) { error = genericError }
        finally { loading = false }
    }
    fun delete() {
        if (deleting || eligibility?.eligible != true || password.isBlank() || confirmation != "DELETE") return
        deleting = true
        error = null
        val input = AccountDeletionRequest(password, confirmation)
        password = ""
        confirmation = ""
        scope.launch {
            try {
                val response = api.deleteAccount(input)
                val body = response.body()
                val result = body?.deletion
                check(response.isSuccessful && body?.success == true && result?.sessionRevoked == true && result.status in setOf("COMPLETED_WITH_RETENTION", "PENDING_CLEANUP")) {
                    deletionError(response, body?.error ?: genericError)
                }
                onClosed(deletionSuccessMessage(result!!.status, language))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalStateException) { error = failure.message ?: genericError }
            catch (_: Exception) { error = t("We could not confirm the result. Your account may already be closed. Contact info@passo.live if you cannot sign in.", "ውጤቱን ማረጋገጥ አልቻልንም። መለያዎ ተዘግቶ ሊሆን ይችላል። መግባት ካልቻሉ info@passo.live ያግኙ።", "Bu'aa isaa mirkaneessuu hin dandeenye. Herregni kee cufamee jiraachuu danda'a. Seenuu yoo dadhabde info@passo.live qunnami.") }
            finally { password = ""; deleting = false }
        }
    }
    Surface(Modifier.fillMaxSize(), color = PrivacyCream, contentColor = PrivacyNavy) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row {
                IconButton(onClick = onBack, enabled = !deleting, modifier = Modifier.testTag("privacy_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "ተመለስ", "Duubatti"))
                }
                Column(Modifier.padding(top = 6.dp)) {
                    Text(t("Privacy & your account", "ግላዊነት እና መለያዎ", "Iccitii fi herrega kee"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("PASSO", color = PrivacyTeal, fontWeight = FontWeight.SemiBold)
                }
            }
            com.example.ui.components.EnvironmentSettingsButton(enabled = !deleting)
            TextButton(enabled = !deleting, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://passo.live/privacy/"))) }
                    .onFailure { error = genericError }
            }, modifier = Modifier.testTag("privacy_policy")) {
                Text(t("Read our privacy policy", "የግላዊነት ፖሊሲያችንን ያንብቡ", "Imaammata iccitii keenya dubbisi"), color = PrivacyTeal)
            }
            if (loading || deleting) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("privacy_loading"), color = PrivacyTeal)
            error?.let {
                Surface(color = Color(0xFFFFE9E7), shape = RoundedCornerShape(16.dp)) { Text(it, Modifier.padding(16.dp).testTag("privacy_error")) }
                if (!deleting && eligibility == null) TextButton(onClick = { refresh++ }, modifier = Modifier.testTag("privacy_retry")) { Text(t("Try again", "ደግመው ይሞክሩ", "Irra deebi'i")) }
            }
            eligibility?.let { details ->
                Text(t("Delete your account", "መለያዎን ይሰርዙ", "Herrega kee haqi"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(t("Review what will happen before continuing. This closes your account; it is not a sign-out button.", "ከመቀጠልዎ በፊት የሚከናወነውን ያንብቡ። ይህ መለያዎን ይዘጋል።", "Itti fufuu dura waan ta'u ilaali. Kun herrega kee cufa; ba'uu qofa miti."))
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        details.consequences.forEach { Text("• $it") }
                        if (details.retainedCategories.isNotEmpty()) {
                            Text(t("Records that will be retained", "የሚቀመጡ መዝገቦች", "Galmeewwan hafan"), fontWeight = FontWeight.Bold)
                            details.retainedCategories.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                details.blockers.forEach { blocker ->
                    Surface(color = Color(0xFFFFEFCC), shape = RoundedCornerShape(14.dp)) {
                        Text(blocker.message, Modifier.fillMaxWidth().padding(14.dp).testTag("privacy_blocker"))
                    }
                }
                if (details.eligible) {
                    OutlinedTextField(password, { password = it }, label = { Text(t("Current password", "የአሁኑ የይለፍ ቃል", "Jecha iccitii ammaa")) },
                        enabled = !deleting, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("privacy_password"))
                    OutlinedTextField(confirmation, { confirmation = it }, label = { Text(t("Type DELETE to confirm", "ለማረጋገጥ DELETE ይጻፉ", "Mirkaneessuuf DELETE barreessi")) },
                        enabled = !deleting, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("privacy_confirmation"))
                    Button(onClick = { confirmDialog = true }, enabled = !deleting && password.isNotBlank() && confirmation == "DELETE",
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA72D2D)), modifier = Modifier.fillMaxWidth().testTag("privacy_delete")) {
                        Text(t("Delete my account", "መለያዬን ሰርዝ", "Herrega koo haqi"))
                    }
                }
                TextButton(enabled = !deleting, onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:info@passo.live?subject=Account%20privacy%20help"))) }.onFailure { error = genericError } }) {
                    Text(t("Contact support", "ድጋፍ ያግኙ", "Deeggarsa qunnami") + " · info@passo.live", color = PrivacyTeal)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmDialog) AlertDialog(onDismissRequest = { confirmDialog = false },
        title = { Text(t("Permanently close this account?", "ይህን መለያ በቋሚነት ይዝጉ?", "Herrega kana dhaabbataan cufuu?")) },
        text = { Text(t("Your access will end. The records listed above may remain as described in the privacy policy.", "የመለያዎ መዳረሻ ይዘጋል። ከላይ የተዘረዘሩት መዝገቦች በግላዊነት ፖሊሲው መሠረት ሊቀመጡ ይችላሉ።", "Herrega kana seenuun ni dhaabata. Galmeewwan armaan olii akka imaammata iccitii keessatti ibsametti hafuu danda'u.")) },
        confirmButton = { TextButton(onClick = { confirmDialog = false; delete() }, modifier = Modifier.testTag("privacy_confirm_final")) { Text(t("Yes, delete my account", "አዎ፣ መለያዬን ሰርዝ", "Eeyyee, herrega koo haqi")) } },
        dismissButton = { TextButton(onClick = { confirmDialog = false }) { Text(t("Keep my account", "መለያዬን አቆይ", "Herrega koo tursi")) } })
}
