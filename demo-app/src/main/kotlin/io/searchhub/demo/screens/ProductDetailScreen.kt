package io.searchhub.demo.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.searchhub.collector.SearchCollector
import io.searchhub.demo.FakeData

@Composable
fun ProductDetailScreen(productId: String, keywords: String, onBasket: () -> Unit) {
    val product = FakeData.productById(productId)

    if (product == null) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Produkt nicht gefunden: $productId")
        }
        return
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(product.name, fontSize = 20.sp)
        Text("${product.price} €", fontSize = 16.sp)
        Text("keywords: $keywords", fontSize = 12.sp)

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("Ähnliche Produkte", fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))

        FakeData.relatedProducts.forEach { related ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        SearchCollector.trackAssociatedProductClick(
                            related.id, related.position, keywords
                        )
                    }
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(related.name)
                        Text("(associated-product-click)", fontSize = 11.sp)
                    }
                    Text("${related.price} €")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                SearchCollector.trackBasket(productId, product.price)
                onBasket()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("In den Warenkorb")
        }
    }
}
