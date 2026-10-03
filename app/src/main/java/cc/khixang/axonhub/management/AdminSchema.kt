package cc.khixang.axonhub.management

import android.content.Context
import cc.khixang.axonhub.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

@Serializable data class AdminField(
    val name: String, val type: String, val description: String = "",
    val hasDefault: Boolean = false, val default: JsonElement? = null,
) { val required get() = type.endsWith("!") }

@Serializable data class AdminSchemaType(val kind: String, val fields: List<AdminField> = emptyList(), val values: List<String> = emptyList())

@Serializable data class AdminOperation(
    val id: String, val name: String, val documentKey: String, val root: String, val kind: String,
    val group: String, val variables: List<AdminField> = emptyList(), val entity: String = "",
    val verification: String = "", val destructive: Boolean = false, val replacement: Boolean = false,
    val allowedInputFields: List<String> = emptyList(), val source: String = "",
    val secretRead: Boolean = false, val asyncEffect: Boolean = false,
) { val mutation get() = kind == "mutation" }

@Serializable data class AdminSchema(val revision: String, val types: Map<String, AdminSchemaType>, val operations: List<AdminOperation>) {
    fun base(type: String) = type.replace("!", "").replace("[", "").replace("]", "")
    fun element(type: String): String = type.removeSuffix("!").let { if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1) else it }
    fun operation(id: String): AdminOperation = operations.firstOrNull { it.id == id } ?: error("Missing operation: $id")

    fun defaultValue(type: String, depth: Int = 0): JsonElement {
        if (type.removeSuffix("!").startsWith("[")) return JsonArray(emptyList())
        if (depth > 20) return buildJsonObject {}
        types[base(type)]?.let { info ->
            if (info.kind == "enum") return JsonPrimitive(info.values.firstOrNull().orEmpty())
            if (info.kind == "object") return buildJsonObject {
                info.fields.forEach { field ->
                    if (field.hasDefault && field.default != null) put(field.name, field.default)
                    else if (field.required) put(field.name, defaultValue(field.type, depth + 1))
                }
            }
        }
        return when (base(type)) {
            "Boolean" -> JsonPrimitive(false)
            "Int", "Float" -> JsonPrimitive(0)
            "JSONRawMessage", "JSONRawMessageInput" -> buildJsonObject {}
            else -> JsonPrimitive("")
        }
    }

    fun project(value: JsonElement, type: String): JsonElement {
        if (value is JsonNull) return value
        if (type.removeSuffix("!").startsWith("[")) return JsonArray(value.arr.map { project(it, element(type)) })
        val info = types[base(type)] ?: return value
        if (info.kind != "object") return value
        return buildJsonObject { info.fields.forEach { f -> value.obj[f.name]?.takeUnless { it is JsonNull }?.let { put(f.name, project(it, f.type)) } } }
    }

    fun validate(value: JsonElement, fields: List<AdminField>, mutation: Boolean) {
        val obj = value as? JsonObject ?: throw IllegalArgumentException("Expected object")
        if (!obj.keys.all { key -> fields.any { it.name == key } }) throw IllegalArgumentException("Unsupported field")
        fields.forEach { field ->
            val item = obj[field.name]
            if (item == null) { if (field.required && !field.hasDefault) throw IllegalArgumentException("Required: ${field.name}") }
            else validateValue(item, field.type, field.name, mutation)
        }
    }

    private fun validateValue(value: JsonElement, type: String, path: String, mutation: Boolean) {
        if (value is JsonNull) {
            if (type.endsWith("!") || mutation) throw IllegalArgumentException("Null is not a clear operation: $path")
            return
        }
        if (type.removeSuffix("!").startsWith("[")) {
            val array = value as? JsonArray ?: throw IllegalArgumentException("Expected list: $path")
            array.forEachIndexed { i, item -> validateValue(item, element(type), "$path[$i]", mutation) }; return
        }
        types[base(type)]?.let { info ->
            if (info.kind == "object") { validate(value, info.fields, mutation); return }
            if (info.kind == "enum") {
                if (value.text !in info.values) throw IllegalArgumentException("Invalid enum: $path")
                return
            }
        }
        when (base(type)) {
            "Boolean" -> require(value is JsonPrimitive && value.booleanOrNull != null) { "Expected boolean: $path" }
            "Int" -> require(value is JsonPrimitive && value.intOrNull != null) { "Expected integer: $path" }
            "Float" -> require(value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true) { "Expected finite number: $path" }
            "Decimal", "DecimalInput" -> require(ManagementFormat.parse(value.text) != null) { "Expected decimal: $path" }
            "JSONRawMessage", "JSONRawMessageInput" -> require(!value.text.startsWith("__AXONHUB_INVALID__")) { "Expected valid JSON: $path" }
            else -> require(value is JsonPrimitive && value.isString) { "Expected text: $path" }
        }
    }
}

class AdminCatalog(context: Context, private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) {
    val schema: AdminSchema = context.assets.open("AdminSchema.json").bufferedReader().use { json.decodeFromString(AdminSchema.serializer(), it.readText()) }
    private val documents: Map<String, String> = context.assets.open("admin_documents.json").bufferedReader().use {
        json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it.readText())
    }
    fun operation(id: String) = schema.operation(id)
    fun document(operation: AdminOperation): String = documents[operation.documentKey] ?: error("Missing GraphQL document: ${operation.documentKey}")
}
