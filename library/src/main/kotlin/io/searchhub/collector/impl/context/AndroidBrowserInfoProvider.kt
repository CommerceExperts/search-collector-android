package io.searchhub.collector.impl.context

import android.os.Build
import io.searchhub.collector.interfaces.BrowserInfoProvider
import java.util.Locale

class AndroidBrowserInfoProvider : BrowserInfoProvider {

    override suspend fun getUserAgent(): String =
        System.getProperty("http.agent") ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    override suspend fun isTouchDevice(): Boolean = true

    override suspend fun getLanguage(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Locale.getDefault().toLanguageTag()
        } else {
            Locale.getDefault().toString()
        }
}
