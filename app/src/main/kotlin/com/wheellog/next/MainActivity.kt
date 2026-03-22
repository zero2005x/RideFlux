package com.wheellog.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.wheellog.next.core.ui.theme.RideFluxTheme
import com.wheellog.next.navigation.RideFluxNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RideFluxTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RideFluxNavHost()
                }
            }
        }
    }
}
