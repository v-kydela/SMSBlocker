package com.tharos.smsblocker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tharos.smsblocker.ui.theme.SMSBlockerTheme

import android.provider.BlockedNumberContract
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SMSBlockerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SmsBlockerScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun SmsBlockerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val canBlockNumbers = remember {
        try {
            BlockedNumberContract.canCurrentUserBlockNumbers(context)
        } catch (_: Exception) {
            false
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions.values.all { it }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "SMS Blocker",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "SMS Permission: ${if (hasPermission) "✅ Granted" else "❌ Missing"}")
        Text(text = "System Blocking: ${if (canBlockNumbers) "✅ Available" else "⚠️ Limited (Need to be Default App)"}")
        
        if (!hasPermission) {
            Button(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.READ_SMS
                        )
                    )
                },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(text = "Grant SMS Permissions")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Currently blocking messages containing:",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
        )
        Text(
            text = "'Stop2End'",
            color = Color.Red,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SMSBlockerTheme {
        SmsBlockerScreen()
    }
}