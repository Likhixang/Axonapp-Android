package cc.khixang.axonhub

import android.app.Application
import cc.khixang.axonhub.data.AxonRepository
import cc.khixang.axonhub.network.AxonApi
import cc.khixang.axonhub.gateway.GatewayService
import cc.khixang.axonhub.management.AdminCatalog
import cc.khixang.axonhub.management.AdminService
import cc.khixang.axonhub.observability.AnalyticsService
import cc.khixang.axonhub.observability.ObservabilityService
import cc.khixang.axonhub.playground.PlaygroundService
import cc.khixang.axonhub.storage.InstanceStore
import cc.khixang.axonhub.storage.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AxonHubApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository by lazy { AxonRepository(InstanceStore(this), SecureCredentialStore(this), AxonApi(), appScope) }
    val gateway by lazy { GatewayService(repository) }
    val adminCatalog by lazy { AdminCatalog(this) }
    val admin by lazy { AdminService(repository, adminCatalog) }
    val observability by lazy { ObservabilityService(repository) }
    val analytics by lazy { AnalyticsService(repository) }
    val playground by lazy { PlaygroundService(repository) }
    val settings by lazy { SettingsManager(this) }
}
