package com.example.ui.screens.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.core.localization.AppLanguage
import com.example.ui.screens.intercity.MobilityLocationPicker

/** One address field, with the shared searchable map picker inside its map action. */
@Composable
internal fun AuthRouteLocationField(draft: AuthDraft, pickup: Boolean, label: String, tag: String,
    enabled: Boolean, language: AppLanguage) {
    var open by remember { mutableStateOf(false) }
    if (open) MobilityLocationPicker(if (pickup) "Choose pickup location" else "Choose destination location",
        if (pickup) draft.pickupPoint else draft.destinationPoint, { open = false },
        { point -> if (pickup) draft.pickupPoint = point else draft.destinationPoint = point; open = false },
        onPlaceName = { name -> if (pickup) draft.pickup = name.take(120) else draft.destination = name.take(120) },
        language = language, initialLabel = if (pickup) draft.pickup else draft.destination)
    AuthField(if (pickup) draft.pickup else draft.destination,
        { value -> if (pickup) { draft.pickup = value.take(120); draft.pickupPoint = null }
            else { draft.destination = value.take(120); draft.destinationPoint = null } }, label,
        Modifier.fillMaxWidth().testTag(tag), enabled = enabled, language = language,
        leadingIcon = Icons.Default.LocationOn, onMap = { open = true })
}
