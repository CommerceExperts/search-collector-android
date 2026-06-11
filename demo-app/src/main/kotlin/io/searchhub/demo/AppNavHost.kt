package io.searchhub.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.searchhub.demo.screens.CheckoutScreen
import io.searchhub.demo.screens.ProductDetailScreen
import io.searchhub.demo.screens.ResultsScreen
import io.searchhub.demo.screens.SearchScreen
import kotlinx.coroutines.launch
import android.net.Uri

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transport = (context.applicationContext as? DemoApplication
        ?: error("Application must extend DemoApplication")).recordingTransport

    fun resetFlow() {
        transport.clear()
        navController.navigate("search") {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    Scaffold(
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(onClick = { resetFlow() }) {
                    Text("🔄")
                }
                FloatingActionButton(onClick = {
                    val events = transport.getEvents()
                    if (events.isEmpty()) {
                        Toast.makeText(context, "Noch keine Events aufgezeichnet", Toast.LENGTH_SHORT).show()
                        return@FloatingActionButton
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            Toast.makeText(context, "Storage-Permission erforderlich (Einstellungen → App → Berechtigungen)", Toast.LENGTH_LONG).show()
                            return@FloatingActionButton
                        }
                    }
                    scope.launch {
                        try {
                            val path = exportEvents(context, events)
                            Toast.makeText(context, "Gespeichert: $path", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text("💾")
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "search",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("search") {
                SearchScreen(
                    onSearch = { keywords ->
                        navController.navigate("results/${keywords.encode()}")
                    }
                )
            }
            composable(
                route = "results/{keywords}",
                arguments = listOf(navArgument("keywords") { type = NavType.StringType })
            ) { backStackEntry ->
                val keywords = backStackEntry.arguments?.getString("keywords").decodeOrEmpty()
                ResultsScreen(
                    keywords = keywords,
                    onProductClick = { productId ->
                        navController.navigate("detail/${productId.encode()}/${keywords.encode()}")
                    }
                )
            }
            composable(
                route = "detail/{productId}/{keywords}",
                arguments = listOf(
                    navArgument("productId") { type = NavType.StringType },
                    navArgument("keywords") { type = NavType.StringType },
                )
            ) { backStackEntry ->
                val productId = backStackEntry.arguments?.getString("productId").decodeOrEmpty()
                val keywords = backStackEntry.arguments?.getString("keywords").decodeOrEmpty()
                ProductDetailScreen(
                    productId = productId,
                    keywords = keywords,
                    onBasket = {
                        navController.navigate("checkout/${productId.encode()}/${keywords.encode()}")
                    }
                )
            }
            composable(
                route = "checkout/{productId}/{keywords}",
                arguments = listOf(
                    navArgument("productId") { type = NavType.StringType },
                    navArgument("keywords") { type = NavType.StringType },
                )
            ) { backStackEntry ->
                val productId = backStackEntry.arguments?.getString("productId").decodeOrEmpty()
                val keywords = backStackEntry.arguments?.getString("keywords").decodeOrEmpty()
                CheckoutScreen(
                    productId = productId,
                    keywords = keywords,
                    onRestart = { resetFlow() }
                )
            }
        }
    }
}

private fun String.encode(): String = Uri.encode(this)
private fun String?.decodeOrEmpty(): String = Uri.decode(this.orEmpty())
