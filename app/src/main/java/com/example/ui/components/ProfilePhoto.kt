package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun ProfilePhoto(
    dataUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    circular: Boolean = true
) {
    val bitmap = remember(dataUrl) {
        try {
            val encoded = dataUrl?.substringAfter(',', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
            encoded?.let { BitmapFactory.decodeByteArray(Base64.decode(it, Base64.DEFAULT), 0, Base64.decode(it, Base64.DEFAULT).size) }
        } catch (_: Exception) {
            null
        }
    }
    val shape = if (circular) CircleShape else RoundedCornerShape(12.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "$name profile photo",
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape)
        )
    } else {
        val initials = name.trim().split(Regex("\\s+"))
            .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
            .take(2).joinToString("").ifBlank { "RP" }
        Box(
            modifier = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
