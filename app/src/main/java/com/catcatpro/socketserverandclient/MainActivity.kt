package com.catcatpro.socketserverandclient

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.catcatpro.socketserverandclient.ui.ServerView
import com.catcatpro.socketserverandclient.ui.theme.SocketServerAndClientTheme
import com.catcatpro.socketserverandclient.ui.views.ClientView


class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_CODE_NEARBY_WIFI = 1001
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        // Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES
            ), REQUEST_CODE_NEARBY_WIFI)
        } else {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            ), REQUEST_CODE_NEARBY_WIFI)
        }

        setContent {
            SocketServerAndClientTheme {
                Scaffold(){ innerPadding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(  innerPadding),
//                        color = MaterialTheme.colorScheme.background
                    ) {
                        App()
                    }
                }

            }
        }
    }
}


@Composable
fun App(){
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("服务器", "客户端")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(text = title) }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> ServerView()
            1 -> ClientView()
        }
    }
}
//
//@Composable
//fun Greeting(name: String, modifier: Modifier = Modifier) {
//    Text(
//        text = "Hello $name!",
//        modifier = modifier
//    )
//}
//
//@Preview(showBackground = true)
//@Composable
//fun GreetingPreview() {
//    SocketServerAndClientTheme {
//        Greeting("Android")
//    }
//}