package io.searchhub.collector.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EventType(val value: String) {
    @SerialName("browser") BROWSER("browser"),
    @SerialName("instant-search") INSTANT_SEARCH("instant-search"),
    @SerialName("fired-search") FIRED_SEARCH("fired-search"),
    @SerialName("suggest-search") SUGGEST_SEARCH("suggest-search"),
    @SerialName("suggest-product-click") SUGGEST_PRODUCT_CLICK("suggest-product-click"),
    @SerialName("search") SEARCH("search"),
    @SerialName("redirect") REDIRECT("redirect"),
    @SerialName("impression") IMPRESSION("impression"),
    @SerialName("product") PRODUCT("product"),
    @SerialName("associated-product") ASSOCIATED_PRODUCT("associated-product"),
    @SerialName("basket") BASKET("basket"),
    @SerialName("checkout") CHECKOUT("checkout"),
}

@Serializable
enum class SearchAction(val value: String) {
    @SerialName("search") SEARCH("search"),
    @SerialName("search-refinement") SEARCH_REFINEMENT("search-refinement"),
    @SerialName("search-pagination") SEARCH_PAGINATION("search-pagination"),
    @SerialName("search-refinement-pagination") SEARCH_REFINEMENT_PAGINATION("search-refinement-pagination"),
}

@Serializable
enum class TrailType(val value: String) {
    @SerialName("main") MAIN("main"),
    @SerialName("associated") ASSOCIATED("associated"),
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR, SILENT }

enum class BufferedEventsTimestamp {
    /** Use timestamp from when the tracking call was originally made (default). */
    ORIGINAL,
    /** Use current time when configure() replays the buffer. */
    REPLAY,
}

data class TrailData(
    val timestamp: Long,
    val query: String,
    val type: TrailType,
)

data class CheckoutProduct(
    val id: String,
    val price: Double,
    val quantity: Int,
)
