package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.ui.viewmodel.MainViewModel

@Composable
fun PrivateMediaUploadButton(
    viewModel: MainViewModel,
    assetType: String,
    label: String,
    imagesOnly: Boolean = false
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) uploadSelectedFile(context, uri, assetType, imagesOnly, viewModel)
    }
    Button(onClick = { picker.launch(if (imagesOnly) "image/*" else "*/*") }) {
        Text(label)
    }
}

private fun uploadSelectedFile(
    context: Context,
    uri: Uri,
    assetType: String,
    imagesOnly: Boolean,
    viewModel: MainViewModel
) {
    try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.lowercase().orEmpty()
        val isImage = mime in setOf("image/jpeg", "image/png", "image/webp")
        val isPdf = mime == "application/pdf"
        if ((!isImage && !isPdf) || (imagesOnly && !isImage)) {
            throw IllegalArgumentException("Choose a JPG, PNG, WebP image or PDF where allowed.")
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not read the selected file.")
        if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) {
            throw IllegalArgumentException("File must be between 1 byte and 5 MB.")
        }
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "upload"
        viewModel.uploadProfileMedia(
            assetType = assetType,
            fileName = fileName,
            contentType = mime,
            dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        )
    } catch (e: Exception) {
        viewModel.reportMediaUploadError(e.message ?: "Could not read this file.")
    }
}
