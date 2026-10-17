package com.example.ui.screens.profile

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.api.ApiClient
import com.example.data.api.CompletionProfile
import com.example.data.api.ProfileApi
import com.example.data.api.ProfileResponse
import com.example.data.api.ProfileUpdateInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val MAX_FILE_BYTES = 5 * 1024 * 1024

private fun documentLabel(kind: String): String = when (kind) {
    "LICENSE" -> "Driving licence"
    "VEHICLE_REGISTRATION" -> "Vehicle registration"
    "INSURANCE" -> "Insurance"
    "PHOTO" -> "Profile photo"
    "ID" -> "Identity document"
    else -> kind.lowercase().replace('_', ' ')
}

private fun readLimited(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_FILE_BYTES) { "Each file must be 5 MB or smaller." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun checkedProfile(response: Response<ProfileResponse>): CompletionProfile {
    val body = response.body()
    if (response.isSuccessful && body?.success == true && body.profile != null) return body.profile
    val message = runCatching { JSONObject(response.errorBody()?.string() ?: "{}").optString("error") }.getOrNull()
    throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: body?.error ?: "Unable to update profile. Please try again.")
}

/** A lightweight sign-in reminder; failures retain an actionable registration link. */
@Composable
fun ProfileCompletionPrompt(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    var profile by remember { mutableStateOf<CompletionProfile?>(null) }
    var finishedLoading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { profile = checkedProfile(ApiClient.createFeatureService(ProfileApi::class.java).getMyProfile()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The profile menu remains available for retry. */ }
        finally { finishedLoading = true }
    }
    if (finishedLoading && profile?.complete != true) {
        Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.Black, contentColor = Color.White) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Complete your registration", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Add your photo and identity document for your daily commute.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Complete profile") }
            }
        }
    }
}

/** Private uploads are offered after sign-in; identity files never enter public image storage. */
@Composable
fun ProfileCompletionScreen(role: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { ApiClient.createFeatureService(ProfileApi::class.java) }
    var profile by remember { mutableStateOf<CompletionProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busyKind by remember { mutableStateOf<String?>(null) }
    var pickingKind by remember { mutableStateOf("PHOTO") }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var vehicleColor by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    val driver = role.equals("DRIVER", ignoreCase = true)

    fun applyProfile(value: CompletionProfile) {
        profile = value
        contactName = value.emergencyContactName
        contactPhone = value.emergencyContactPhone
        vehicleColor = value.vehicleColor
    }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        try { applyProfile(checkedProfile(api.getMyProfile())) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load your profile. Try again." }
        finally { loading = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val kind = pickingKind
            busyKind = kind
            error = null
            message = null
            scope.launch {
                try {
                    val (bytes, mime) = withContext(Dispatchers.IO) {
                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        val bytes = context.contentResolver.openInputStream(uri)?.use(::readLimited)
                            ?: throw IllegalStateException("This file cannot be read. Choose it again.")
                        bytes to mime
                    }
                    val updated = checkedProfile(api.uploadDocument(kind, bytes.toRequestBody(mime.toMediaType())))
                    profile = updated
                    message = "${documentLabel(kind)} uploaded."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Upload failed. Please choose the file again." }
                finally { busyKind = null }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F5F6)) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column {
                    Text("Your profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (driver) "Documents & emergency contact" else "Complete your daily commute registration", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Loading your registration…")
            }
            profile?.let { value ->
                Surface(shape = RoundedCornerShape(24.dp), color = Color.Black, contentColor = Color.White) {
                    Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        AuthenticatedProfilePhoto(value.userId, Modifier.size(68.dp), refreshKey = value.documents.firstOrNull { it.kind == "PHOTO" }?.uploadedAt)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(if (value.complete) "You're all set" else "Finish setting up", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(if (value.complete) "Your required details are saved." else "${value.missing.size} required ${if (value.missing.size == 1) "item" else "items"} remaining", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text("Required documents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Use clear, readable files. JPEG, PNG or PDF, up to 5 MB each. Your photo must be JPEG or PNG.", style = MaterialTheme.typography.bodyMedium)
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        value.required.forEachIndexed { index, kind ->
                            val uploaded = value.documents.any { it.kind == kind }
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (uploaded) Icons.Default.CheckCircle else Icons.Default.Description, contentDescription = null, tint = if (uploaded) Color(0xFF087F5B) else Color.DarkGray)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(documentLabel(kind), fontWeight = FontWeight.SemiBold)
                                    Text(if (busyKind == kind) "Uploading…" else if (uploaded) "Uploaded" else "Required", style = MaterialTheme.typography.bodySmall)
                                }
                                if (busyKind == kind) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                else TextButton(enabled = busyKind == null, onClick = {
                                    pickingKind = kind
                                    picker.launch(if (kind == "PHOTO") "image/*" else "*/*")
                                }) { Text(if (uploaded) "Replace" else "Upload", color = Color.Black) }
                            }
                            if (index < value.required.lastIndex) HorizontalDivider(color = Color(0xFFEEEEEE))
                        }
                    }
                }

                if (driver) {
                    Text("On the road", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Passengers see your profile photo and vehicle color. Your emergency contact is for authorized staff.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = vehicleColor, onValueChange = { vehicleColor = it.take(40) }, label = { Text("Vehicle color") }, singleLine = true, enabled = busyKind == null, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = contactName, onValueChange = { contactName = it.take(100) }, label = { Text("Emergency contact name") }, singleLine = true, enabled = busyKind == null, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = contactPhone, onValueChange = { contactPhone = it.take(24) }, label = { Text("Emergency contact phone") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, enabled = busyKind == null, modifier = Modifier.fillMaxWidth())
                    Button(enabled = busyKind == null && contactName.trim().length >= 2 && contactPhone.trim().length >= 7 && vehicleColor.trim().length >= 2,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), onClick = {
                            busyKind = "DETAILS"
                            error = null
                            message = null
                            scope.launch {
                                try {
                                    applyProfile(checkedProfile(api.updateMyProfile(ProfileUpdateInput(contactName.trim(), contactPhone.trim(), vehicleColor.trim()))))
                                    message = "Emergency contact and vehicle color saved."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: "Unable to save. Try again." }
                                finally { busyKind = null }
                            }
                        }) { Text(if (busyKind == "DETAILS") "Saving…" else "Save details", modifier = Modifier.padding(8.dp)) }
                }
            }
            message?.let { Text(it, color = Color(0xFF087F5B)) }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (profile == null && !loading) Button(onClick = { refresh++ }) { Text("Try again") }
            }
            Text("Identity documents are private and available only to you and your authorized administrator or transport company.", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Uses the authenticated API, rather than exposing private image URLs to a public image loader. */
@Composable
fun AuthenticatedProfilePhoto(userId: String, modifier: Modifier = Modifier, refreshKey: String? = null) {
    var bitmap by remember(userId, refreshKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(userId, refreshKey) {
        bitmap = null
        if (userId.isNotBlank()) {
            try {
                val response = ApiClient.createFeatureService(ProfileApi::class.java).getPhoto(userId)
                val loaded = withContext(Dispatchers.IO) {
                    response.body()?.use { body ->
                        if (!response.isSuccessful || body.contentLength() > MAX_FILE_BYTES) return@use null
                        val bytes = body.byteStream().use(::readLimited)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sampleSize = 1
                        while (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 512) sampleSize *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })?.asImageBitmap()
                    }
                }
                bitmap = loaded
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* An unavailable private photo has a neutral placeholder. */ }
        }
    }
    Box(modifier = modifier.clip(CircleShape).background(Color(0xFFE4E6E8)), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded, contentDescription = "Profile photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.Person, contentDescription = "Profile photo not uploaded", tint = Color(0xFF73777B), modifier = Modifier.fillMaxSize().padding(12.dp))
    }
}
