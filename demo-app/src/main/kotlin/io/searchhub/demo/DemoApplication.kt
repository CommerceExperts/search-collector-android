package io.searchhub.demo

import android.app.Application
import io.searchhub.collector.SearchCollector
import io.searchhub.collector.model.DependencyOverrides
import io.searchhub.collector.model.SearchCollectorConfig

class DemoApplication : Application() {

    val recordingTransport = RecordingTransport(queueUrl = FakeData.FAKE_ENDPOINT)

    override fun onCreate() {
        super.onCreate()
        SearchCollector.configure(
            SearchCollectorConfig(
                context = this,
                endpoint = FakeData.FAKE_ENDPOINT,
                channel = FakeData.DEFAULT_CHANNEL,
                overrides = DependencyOverrides(transport = recordingTransport),
            )
        )
        SearchCollector.initialize()
    }
}
