package cc.khixang.axonhub.core

import kotlinx.serialization.json.*

val JsonElement?.obj: JsonObject get() = this as? JsonObject ?: JsonObject(emptyMap())
val JsonElement?.arr: JsonArray get() = this as? JsonArray ?: JsonArray(emptyList())
val JsonElement?.text: String get() = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
val JsonElement?.longOrNull: Long? get() = (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
val JsonElement?.intOrNull: Int? get() = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
val JsonElement?.doubleOrNull: Double? get() = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
val JsonElement?.boolOrNull: Boolean? get() = (this as? JsonPrimitive)?.contentOrNull?.let {
    when (it.lowercase()) { "true" -> true; "false" -> false; else -> null }
}
operator fun JsonElement?.get(key: String): JsonElement = obj[key] ?: JsonNull

fun JsonElement.redacted(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map { it.redacted() })
    is JsonObject -> JsonObject(mapValues { (key, value) -> if (SensitiveFields.matches(key)) JsonPrimitive("••••••") else value.redacted() })
    else -> this
}

object SensitiveFields {
    private val names = setOf("authorization", "cookie", "setcookie", "apikey", "apikeys", "token", "accesstoken", "refreshtoken", "password", "secret", "secretkey", "accesskey", "authcookie", "clientsecret", "privatekey", "credentials", "managementapikey")
    fun matches(name: String): Boolean {
        val key = name.lowercase().replace("-", "").replace("_", "")
        return key in names || key.endsWith("token") || key.endsWith("password") || key.endsWith("secret")
    }
}
