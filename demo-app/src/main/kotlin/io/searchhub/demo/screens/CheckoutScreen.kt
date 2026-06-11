package io.searchhub.demo.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.searchhub.collector.SearchCollector
import io.searchhub.collector.model.CheckoutProduct
import io.searchhub.demo.FakeData

@Composable
fun CheckoutScreen(productId: String, keywords: String, onRestart: () -> Unit) {
    val product = FakeData.productById(productId)
    var purchased by remember { mutableStateOf(false) }

    if (product == null) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Produkt nicht gefunden: $productId")
        }
        return
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Checkout", fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(product.name, fontSize = 18.sp)
        Text("${product.price} €", fontSize = 16.sp)
        Text("Menge: 1", fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

        if (purchased) {
            Text("✓ Kauf simuliert!", fontSize = 18.sp, color = Color(0xFF2E7D32))
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Neuen Flow starten")
            }
        } else {
            Button(
                onClick = {
                    SearchCollector.trackCheckout(
                        listOf(CheckoutProduct(productId, product.price, quantity = 1))
                    )
                    purchased = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Jetzt kaufen")
            }
        }
    }
}
