package cc.khixang.axonhub.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.khixang.axonhub.core.AxonInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.instanceDataStore by preferencesDataStore("axonhub_instances")

@Serializable data class StoredInstances(val instances: List<AxonInstance> = emptyList(), val selectedId: String = "")

class InstanceStore(private val context: Context, private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val stateKey = stringPreferencesKey("state")
    val state: Flow<StoredInstances> = context.instanceDataStore.data.map { prefs ->
        prefs[stateKey]?.let { runCatching { json.decodeFromString<StoredInstances>(it) }.getOrNull() } ?: StoredInstances()
    }
    suspend fun save(value: StoredInstances) {
        context.instanceDataStore.edit { it[stateKey] = json.encodeToString(StoredInstances.serializer(), value) }
    }
}
