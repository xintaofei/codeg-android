package app.codeg.android.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * The shared Ktor client (OkHttp engine) used for every server. Timeouts are
     * configured on the OkHttp engine rather than via Ktor's `HttpTimeout`
     * plugin: OkHttp's WebSocket correctly ignores the read timeout once upgraded
     * and uses the ping interval for liveness, so a long-idle agent turn over the
     * event stream is never torn down — while ordinary HTTP POSTs still get a 30s
     * ceiling. `expectSuccess = false` so [app.codeg.android.core.network.CodegClient]
     * maps non-2xx responses to its own typed errors.
     *
     * `HttpTimeout` is installed with NO defaults, so it sets nothing on any request by
     * itself: it is only there because the OkHttp engine refuses a per-request `timeout {}`
     * without it — which the one call that may legitimately sit silent for minutes (a file
     * search building a cold workspace index) uses.
     */
    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout)
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
        install(Logging) {
            level = LogLevel.NONE
        }
        engine {
            config {
                retryOnConnectionFailure(true)
                connectTimeout(Duration.ofSeconds(30))
                readTimeout(Duration.ofSeconds(30))
                writeTimeout(Duration.ofSeconds(30))
                pingInterval(Duration.ofSeconds(20))
            }
        }
    }
}
