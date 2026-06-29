package io.searchhub.collector.impl.context

import android.content.Context
import android.os.Build
import io.searchhub.collector.interfaces.ContextProvider
import java.util.Locale

class AndroidContextProvider(
    context: Context,
    initialUrl: String = "",
    initialReferrer: String = "",
) : ContextProvider {

    @Suppress("unused")
    private val appContext: Context = context.applicationContext

    @Volatile private var currentUrl: String = initialUrl
    @Volatile private var referrer: String = initialReferrer

    override suspend fun getCurrentUrl(): String = currentUrl

    override suspend fun getReferrer(): String = referrer

    override suspend fun getUserAgent(): String =
        System.getProperty("http.agent") ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    override suspend fun isTouchDevice(): Boolean = true

    override suspend fun getLanguage(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Locale.getDefault().toLanguageTag()
        } else {
            Locale.getDefault().toString()
        }

    fun setUrl(url: String) { currentUrl = url }

    fun setReferrer(referrer: String) { this.referrer = referrer }

    override fun setContext(url: String, referrer: String) {
        setUrl(url)
        setReferrer(referrer)
    }
}
