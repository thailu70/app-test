package com.example.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.localization.AppLanguage
import com.example.ui.theme.PassoFont

internal val AuthCream = Color(0xFFFFFBF4)
internal val AuthInk = Color(0xFF06263C)
internal val AuthTeal = Color(0xFF00695F)
internal val AuthMint = Color(0xFFE4F3E9)
private val AuthMuted = Color(0xFF627082)
private val AuthBorder = Color(0xFFDADDD8)

internal fun authDesignText(language: AppLanguage, en: String, am: String, om: String): String = when (language) {
    AppLanguage.ENGLISH -> en
    AppLanguage.AMHARIC -> am
    AppLanguage.OROMO -> om
}

/** The four photographic assets contain scenery only. Branding and every control are native. */
@Composable
internal fun AuthPhotoHeader(
    isDriver: Boolean,
    isRegister: Boolean,
    language: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth().testTag("auth_photo_header")) {
        val preferredHeight = if (isRegister) (maxWidth * 0.70f).coerceIn(210.dp, 275.dp)
            else (maxWidth * 0.84f).coerceIn(240.dp, 330.dp)
        val headerHeight = if (constraints.hasBoundedHeight) maxHeight else preferredHeight
        val photo = when {
            isDriver && isRegister -> R.drawable.auth_driver_signup
            isDriver -> R.drawable.auth_driver_login
            isRegister -> R.drawable.auth_passenger_signup
            else -> R.drawable.auth_passenger_login
        }
        Box(Modifier.fillMaxWidth().height(headerHeight).background(AuthCream)) {
            Box(Modifier.fillMaxSize().padding(top = if (isDriver) 64.dp else 94.dp)) {
                Image(painterResource(photo), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    0f to AuthCream.copy(alpha = 0.92f), 0.13f to Color.Transparent,
                    0.78f to Color.Transparent, 1f to AuthCream
                )))
            }
            if (isDriver) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(onClick = onBack, shape = CircleShape, color = AuthCream.copy(alpha = 0.94f),
                        modifier = Modifier.size(34.dp).testTag("auth_header_back")) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, authDesignText(language, "Back", "ተመለስ", "Deebi'i"),
                                Modifier.size(22.dp), tint = AuthInk)
                        }
                    }
                    Column(Modifier.weight(0.7f)) {
                        Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.fillMaxWidth().height(30.dp), contentScale = ContentScale.Fit)
                        Text("Go Further, Together.", color = AuthInk, fontFamily = PassoFont, fontSize = 10.sp,
                            lineHeight = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    AuthLanguageSelector(language, onLanguage, Modifier.weight(1.3f))
                }
            } else Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Surface(onClick = onBack, shape = CircleShape, color = AuthCream.copy(alpha = 0.94f),
                        modifier = Modifier.align(Alignment.CenterStart).size(42.dp).testTag("auth_header_back")) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, authDesignText(language, "Back", "ተመለስ", "Deebi'i"),
                                Modifier.size(23.dp), tint = AuthInk)
                        }
                    }
                    Column(Modifier.align(Alignment.Center).width(170.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.fillMaxWidth().height(40.dp), contentScale = ContentScale.Fit)
                        Text("Go Further, Together.", color = AuthInk, fontFamily = PassoFont, fontSize = 12.sp,
                            lineHeight = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    }
                }
                AuthLanguageSelector(language, onLanguage, Modifier.padding(top = 9.dp, start = 48.dp, end = 48.dp).widthIn(max = 310.dp))
            }
        }
    }
}

@Composable
private fun AuthLanguageSelector(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = AuthCream.copy(alpha = 0.96f), border = BorderStroke(1.dp, AuthBorder), shape = CircleShape, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(3.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AppLanguage.entries.forEach { item ->
                val selected = item == language
                Surface(color = if (selected) AuthTeal else Color.Transparent, shape = CircleShape, modifier = Modifier.weight(if (item == AppLanguage.OROMO) 1.34f else 1f)) {
                    Box(Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onLanguage(item) })
                        .testTag("auth_language_${item.code}").semantics { contentDescription = item.displayName }
                        .heightIn(min = 34.dp).padding(horizontal = 3.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Text(item.displayName, color = if (selected) Color.White else AuthInk, fontFamily = PassoFont,
                            fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, letterSpacing = 0.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AuthRoleBadge(isDriver: Boolean, language: AppLanguage, modifier: Modifier = Modifier) {
    Surface(color = AuthMint, shape = CircleShape, modifier = modifier.testTag("auth_role_badge")) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.DirectionsBus, null, Modifier.size(17.dp), tint = AuthTeal)
            Text(if (isDriver) authDesignText(language, "DRIVER", "ሹፌር", "KONKOLAACHISAA")
                else authDesignText(language, "PASSENGER", "መንገደኛ", "IMALAA"), color = AuthTeal,
                fontFamily = PassoFont, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun AuthIdentityTitle(isDriver: Boolean, isRegister: Boolean, language: AppLanguage, modifier: Modifier = Modifier) {
    val centered = isRegister && !isDriver
    val title = when {
        isDriver && isRegister -> authDesignText(language, "Join the journey.", "የጉዞው አካል ይሁኑ።", "Imalatti makami.")
        isDriver -> authDesignText(language, "Welcome back, driver.", "ሹፌር፣ እንኳን ተመለሱ።", "Baga deebite, konkolaachisaa.")
        isRegister -> authDesignText(language, "Create your account", "መለያዎን ይፍጠሩ", "Herrega kee uumi")
        else -> authDesignText(language, "Welcome back.", "እንኳን ተመለሱ።", "Baga deebite.")
    }
    val subtitle = when {
        isDriver && isRegister -> authDesignText(language, "Create your driver account.", "የሹፌር መለያዎን ይፍጠሩ።", "Herrega konkolaachisaa kee uumi.")
        isDriver -> authDesignText(language, "Safe trips. Brighter tomorrows.", "ደህንነቱ የተጠበቀ ጉዞ። ብሩህ ነገ።", "Imala nageenyaa. Boruu ifaa.")
        isRegister -> authDesignText(language, "Your daily ride starts here.", "የዕለት ጉዞዎ እዚህ ይጀምራል።", "Imalli guyyaa kee asitti jalqaba.")
        else -> authDesignText(language, "Your daily ride is waiting.", "የዕለት ጉዞዎ እየጠበቀዎት ነው።", "Imalli guyyaa kee si eeggachaa jira.")
    }
    val largeTitle = (isDriver && isRegister) || (!isDriver && !isRegister)
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = AuthInk, fontFamily = PassoFont, fontSize = if (largeTitle) 38.sp else if (isRegister && !isDriver) 29.sp else 31.sp,
            lineHeight = if (largeTitle) 43.sp else 35.sp, letterSpacing = (-0.7).sp, fontWeight = FontWeight.ExtraBold,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth().testTag("auth_identity_title"))
        Text(subtitle, color = AuthMuted, fontFamily = PassoFont, fontSize = 16.sp, lineHeight = 21.sp,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
    }
}

/** Zero-based account, OTP and final details steps. OTP completion remains owned by the existing flow. */
@Composable
internal fun AuthStepProgress(step: Int, isDriver: Boolean, language: AppLanguage, modifier: Modifier = Modifier) {
    val active = step.coerceIn(0, 2)
    val labels = listOf(authDesignText(language, "Account", "መለያ", "Herrega"),
        authDesignText(language, "Verify phone", "ስልክ አረጋግጥ", "Bilbila mirkaneessi"),
        if (isDriver) authDesignText(language, "Driver details", "የሹፌር ዝርዝር", "Odeeffannoo konkolaachisaa")
        else authDesignText(language, "Choose route", "መስመር ምረጥ", "Karaa filadhu"))
    Column(modifier.fillMaxWidth().testTag("auth_step_progress").semantics { progressBarRangeInfo = ProgressBarRangeInfo(active.toFloat(), 0f..2f, 1) },
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                Text(label, color = if (index <= active) AuthTeal else AuthMuted, fontFamily = PassoFont,
                    fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Medium,
                    textAlign = when (index) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center },
                    modifier = Modifier.weight(1f).testTag("auth_step_$index"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                Surface(color = if (index <= active) AuthTeal else AuthCream, shape = CircleShape,
                    border = BorderStroke(1.dp, if (index <= active) AuthTeal else AuthBorder), modifier = Modifier.size(13.dp)) {}
                if (index < 2) Box(Modifier.weight(1f).height(1.dp).background(if (index < active) AuthTeal else AuthBorder))
            }
        }
    }
}

/** A native editable field with the mockup's in-field hierarchy, secure masking and visibility toggle. */
@Composable
internal fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    phone: Boolean = false,
    enabled: Boolean = true,
    helper: String? = null,
    language: AppLanguage = AppLanguage.ENGLISH,
    leadingIcon: ImageVector? = null,
    placeholder: String? = null
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val icon = leadingIcon ?: when { password -> Icons.Default.Lock; phone -> Icons.Default.PhoneAndroid; else -> Icons.Default.PersonOutline }
    val iconColor = AuthInk.copy(alpha = if (enabled) 1f else 0.45f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = PassoFont, fontSize = 16.sp, lineHeight = 21.sp,
                color = AuthInk.copy(alpha = if (enabled) 1f else 0.5f), fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(AuthTeal), visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when { phone -> KeyboardType.Phone; password -> KeyboardType.Password; else -> KeyboardType.Text },
                capitalization = if (!phone && !password) KeyboardCapitalization.Words else KeyboardCapitalization.None),
            modifier = modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label },
            decorationBox = { input ->
                Surface(color = Color.Transparent, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, AuthBorder)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pointerInput(enabled) {
                        detectTapGestures { if (enabled) { focus.requestFocus(); keyboard?.show() } }
                    }.padding(start = 14.dp, end = if (password) 4.dp else 14.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null, Modifier.size(23.dp), tint = iconColor)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (phone || value.isNotEmpty()) Text(label, color = AuthMuted, fontFamily = PassoFont, fontSize = 12.sp, lineHeight = 15.sp)
                            Box(Modifier.fillMaxWidth()) {
                                if (value.isEmpty()) Text(placeholder ?: if (phone) "+251 / 09…" else label, color = AuthMuted, fontFamily = PassoFont, fontSize = 15.sp, lineHeight = 21.sp)
                                input()
                            }
                        }
                        if (password) IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.size(42.dp)) {
                            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                authDesignText(language, if (visible) "Hide password" else "Show password", if (visible) "የይለፍ ቃል ደብቅ" else "የይለፍ ቃል አሳይ", if (visible) "Jecha icciitii dhoksi" else "Jecha icciitii agarsiisi"),
                                tint = AuthMuted, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            })
        if (!helper.isNullOrBlank()) Text(helper, color = AuthMuted, fontFamily = PassoFont, fontSize = 11.sp,
            lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 13.dp))
    }
}

@Composable
internal fun AuthPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    Button(onClick = onClick, enabled = enabled && !busy, shape = RoundedCornerShape(19.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AuthTeal, contentColor = Color.White,
            disabledContainerColor = AuthTeal.copy(alpha = 0.38f), disabledContentColor = Color.White.copy(alpha = 0.88f)),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 12.dp), modifier = modifier.fillMaxWidth().heightIn(min = 50.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Spacer(Modifier.size(20.dp))
        Text(label, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, fontFamily = PassoFont,
            fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(24.dp))
    }
}
