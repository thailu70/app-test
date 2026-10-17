package com.example.ui.screens.passenger

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.api.ApiClient
import com.example.ui.screens.profile.AuthenticatedProfilePhoto
import retrofit2.http.GET

data class AssignedDriver(val userId:String,val fullName:String,val phone:String,val vehicleType:String,val vehicleColor:String,val plateNumber:String,val model:String,val routeName:String)
data class AssignedDriverResponse(val success:Boolean,val driver:AssignedDriver?=null)
interface AssignedDriverApi { @GET("api/commute/my-driver") suspend fun getDriver():retrofit2.Response<AssignedDriverResponse> }

@Composable
fun AssignedDriverCard() {
    var driver by remember { mutableStateOf<AssignedDriver?>(null) }
    var message by remember { mutableStateOf("Loading your assigned driver…") }
    val context=LocalContext.current
    LaunchedEffect(Unit) {
        while(true) {
            try { val response=ApiClient.createFeatureService(AssignedDriverApi::class.java).getDriver(); if(response.isSuccessful) { driver=response.body()?.driver;message="Your driver will appear here after a vehicle is assigned." } else message="Could not load your driver. Please try again." } catch (_:Exception) { message="Check your connection to load your driver." }
            kotlinx.coroutines.delay(30000)
        }
    }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Your assigned driver",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        val d=driver
        if(d==null) Text(message) else {
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                AuthenticatedProfilePhoto(userId=d.userId,modifier=Modifier.size(72.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(d.fullName,fontWeight=FontWeight.Bold)
                    Text(d.routeName)
                    Text("${d.vehicleColor} • ${d.model} • ${d.vehicleType}",style=MaterialTheme.typography.bodySmall)
                    Text(d.plateNumber,fontWeight=FontWeight.SemiBold)
                }
            }
            OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:${d.phone}")))},modifier=Modifier.fillMaxWidth()) {Text("Call ${d.phone}")}
        }
    } }
}
