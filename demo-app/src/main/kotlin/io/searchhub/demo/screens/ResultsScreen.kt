package io.searchhub.demo.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.searchhub.collector.SearchCollector
import io.searchhub.collector.model.ProductPosition
import io.searchhub.demo.FakeData

@Composable
fun ResultsScreen(keywords: String, onProductClick: (productId: String) -> Unit) {
    LaunchedEffect(Unit) {
        SearchCollector.trackSearch(keywords, count = 42)
        SearchCollector.trackImpression(
            keywords,
            FakeData.products.map { ProductPosition(it.id, it.position) }
        )
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Ergebnisse für: \"$keywords\"", fontSize = 18.sp)
        Text("42 Treffer", fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        FakeData.products.forEach { product ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        SearchCollector.trackProductClick(product.id, product.position, keywords)
                        onProductClick(product.id)
                    }
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(product.name)
                        Text("Position ${product.position}", fontSize = 12.sp)
                    }
                    Text("${product.price} €")
                }
            }
        }
    }
}
