@file:OptIn(ExperimentalSerializationApi::class)

package io.searchhub.collector.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonClassDiscriminator

@JsonClassDiscriminator("type")
@Serializable
sealed class SearchCollectorEvent {
    abstract val type: EventType
    abstract val timestamp: Long
    abstract val session: String
    abstract val channel: String
    abstract val url: String
    abstract val ref: String

    @Serializable
    @SerialName("browser")
    data class Browser(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val agent: String,
        val touch: Boolean,
        val lang: String,
        @Transient override val type: EventType = EventType.BROWSER,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("instant-search")
    data class InstantSearch(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val keywords: String,
        val query: String = keywords,  // deserialization fallback — always set explicitly by SearchCollectorCore
        @Transient override val type: EventType = EventType.INSTANT_SEARCH,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("fired-search")
    data class FiredSearch(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val keywords: String,
        val query: String = keywords,  // deserialization fallback — always set explicitly by SearchCollectorCore
        @Transient override val type: EventType = EventType.FIRED_SEARCH,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("suggest-search")
    data class SuggestSearch(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val keywords: String,
        val query: String = keywords,  // deserialization fallback — always set explicitly by SearchCollectorCore
        val data: SuggestData,
        @Transient override val type: EventType = EventType.SUGGEST_SEARCH,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("suggest-product-click")
    data class SuggestProductClick(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val keywords: String,
        val query: String = keywords,  // deserialization fallback — always set explicitly by SearchCollectorCore
        val data: SuggestProductData,
        @Transient override val type: EventType = EventType.SUGGEST_PRODUCT_CLICK,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("search")
    data class Search(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val keywords: String,
        val query: String = keywords,  // deserialization fallback — always set explicitly by SearchCollectorCore
        val count: Int,
        val action: SearchAction,
        @Transient override val type: EventType = EventType.SEARCH,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("redirect")
    data class Redirect(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val keywords: String,
        val query: String = keywords,  // deserialization fallback — always set explicitly by SearchCollectorCore
        val resultCount: Int,
        @Transient override val type: EventType = EventType.REDIRECT,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("impression")
    data class Impression(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val query: String,
        val data: List<ProductPosition>,
        @Transient override val type: EventType = EventType.IMPRESSION,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("product")
    data class Product(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val query: String,
        val id: String,
        val position: Int,
        @Transient override val type: EventType = EventType.PRODUCT,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("associated-product")
    data class AssociatedProduct(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val query: String,
        val id: String,
        val position: Int,
        val trailType: TrailType = TrailType.ASSOCIATED,
        @Transient override val type: EventType = EventType.ASSOCIATED_PRODUCT,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("basket")
    data class Basket(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val query: String,
        val id: String,
        val price: Double,
        val trailType: TrailType? = null,
        @Transient override val type: EventType = EventType.BASKET,
    ) : SearchCollectorEvent()

    @Serializable
    @SerialName("checkout")
    data class Checkout(
        override val timestamp: Long,
        override val session: String,
        override val channel: String,
        override val url: String,
        override val ref: String,
        val query: String,
        val id: String,
        val price: Double,
        val quantity: Int,
        val trailType: TrailType? = null,
        @Transient override val type: EventType = EventType.CHECKOUT,
    ) : SearchCollectorEvent()
}

@Serializable
data class SuggestData(
    val prefix: String,
    val position: Int,
)

@Serializable
data class SuggestProductData(
    val prefix: String,
    val position: Int,
    val id: String,
)

@Serializable
data class ProductPosition(
    val id: String,
    val position: Int,
)
