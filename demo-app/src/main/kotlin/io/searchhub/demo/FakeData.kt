package io.searchhub.demo

data class FakeProduct(
    val id: String,
    val name: String,
    val price: Double,
    val position: Int,
)

object FakeData {

    const val DEFAULT_CHANNEL = "demo-app"
    const val FAKE_ENDPOINT = "https://demo.invalid/sqs"

    val products = listOf(
        FakeProduct(id = "prod-1", name = "Slim Jeans", price = 49.99, position = 0),
        FakeProduct(id = "prod-2", name = "Wide Leg Jeans", price = 59.99, position = 1),
        FakeProduct(id = "prod-3", name = "Cargo Jeans", price = 54.99, position = 2),
    )

    val relatedProducts = listOf(
        FakeProduct(id = "related-1", name = "Jeans Jacke Classic", price = 89.99, position = 0),
        FakeProduct(id = "related-2", name = "Jeans Jacke Washed", price = 79.99, position = 1),
    )

    val suggestText = "jeans jacke"
    val suggestProductId = "prod-suggest-1"
    val suggestProductKeywords = "jeans"

    fun productById(id: String): FakeProduct? =
        products.find { it.id == id }
}
