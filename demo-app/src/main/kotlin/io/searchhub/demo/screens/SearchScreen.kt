package io.searchhub.demo.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.searchhub.collector.SearchCollector
import io.searchhub.demo.FakeData
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(onSearch: (keywords: String) -> Unit) {
    var query by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(300L)
            SearchCollector.trackInstantSearch(query)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("SearchHub Demo", fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Suche…") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (query.length >= 2) {
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "🔍 ${FakeData.suggestText}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                SearchCollector.trackFiredSearch(FakeData.suggestText)
                                SearchCollector.trackSuggestClick(
                                    keywords = FakeData.suggestText,
                                    prefix = query,
                                    position = 0,
                                )
                                onSearch(FakeData.suggestText)
                            }
                            .padding(vertical = 8.dp),
                    )
                    Text(
                        text = "📦 Jeans Jacke Classic (Produkt-Vorschlag)",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                SearchCollector.trackSuggestProductClick(
                                    keywords = FakeData.suggestProductKeywords,
                                    prefix = query,
                                    position = 1,
                                    productId = FakeData.suggestProductId,
                                )
                                SearchCollector.trackFiredSearch(FakeData.suggestProductKeywords)
                                onSearch(FakeData.suggestProductKeywords)
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                SearchCollector.trackFiredSearch(query)
                onSearch(query)
            },
            enabled = query.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Suchen")
        }
    }
}
