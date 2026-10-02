package cc.khixang.axonhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.khixang.axonhub.core.*
import cc.khixang.axonhub.management.AdminField
import cc.khixang.axonhub.management.AdminSchema
import kotlinx.serialization.json.*

const val INVALID_SCHEMA_VALUE_PREFIX = "__AXONHUB_INVALID__"

@Composable fun SchemaValueEditor(schema: AdminSchema, type: String, value: JsonElement, onChange: (JsonElement) -> Unit, label: String = type, sensitive: Boolean = false, depth: Int = 0) {
    val clean = type.removeSuffix("!")
    if (clean.startsWith("[") && clean.endsWith("]")) {
        val elementType = clean.substring(1, clean.length - 1); val items = value.arr
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            items.forEachIndexed { index, item -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { SchemaValueEditor(schema, elementType, item, { next -> onChange(JsonArray(items.toMutableList().also { it[index] = next })) }, "${index + 1}", sensitive, depth + 1); TextButton({ onChange(JsonArray(items.toMutableList().also { it.removeAt(index) })) }) { Icon(Icons.Default.Delete, null); Text("Remove") } } } }
            OutlinedButton({ onChange(JsonArray(items + schema.defaultValue(elementType))) }) { Icon(Icons.Default.Add, null); Text("Add item") }
        }; return
    }
    val info = schema.types[schema.base(type)]
    if (info?.kind == "object") {
        var expanded by remember(type, depth) { mutableStateOf(depth < 2) }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()) { Text(if (expanded) "Hide $label" else "Configure $label") }
            if (expanded) ObjectFields(schema, info.fields, value.obj, { onChange(it) }, sensitive, depth + 1)
        } }; return
    }
    if (info?.kind == "enum") { EnumDropdown(label, value.text.ifBlank { info.values.firstOrNull().orEmpty() }, info.values) { onChange(JsonPrimitive(it)) }; return }
    when (schema.base(type)) {
        "Boolean" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Switch(value.boolOrNull ?: false, { onChange(JsonPrimitive(it)) }) }
        "JSONRawMessage", "JSONRawMessageInput", "Any" -> {
            var text by remember(type, label) { mutableStateOf(value.toString()) }
            val valid = runCatching { Json.parseToJsonElement(text) }.isSuccess
            OutlinedTextField(text, { next -> text = next; onChange(runCatching { Json.parseToJsonElement(next) }.getOrElse { JsonPrimitive(INVALID_SCHEMA_VALUE_PREFIX + next) }) }, label = { Text(label) }, supportingText = { if (!valid) Text("Enter valid JSON") }, isError = !valid, minLines = 3, modifier = Modifier.fillMaxWidth())
        }
        "Int", "Float" -> {
            var text by remember(type, label) { mutableStateOf(value.text) }
            val number = if (schema.base(type) == "Int") text.toIntOrNull() else text.toDoubleOrNull()
            OutlinedTextField(text, { next -> text = next; val parsed = if (schema.base(type) == "Int") next.toIntOrNull() else next.toDoubleOrNull(); onChange(parsed?.let(::JsonPrimitive) ?: JsonPrimitive(INVALID_SCHEMA_VALUE_PREFIX + next)) }, label = { Text(label + if (type.endsWith("!")) " *" else "") }, supportingText = { if (number == null) Text("Enter a valid number") }, isError = number == null, modifier = Modifier.fillMaxWidth())
        }
        else -> OutlinedTextField(value.text, { onChange(JsonPrimitive(it)) }, label = { Text(label + if (type.endsWith("!")) " *" else "") }, visualTransformation = if (sensitive || SensitiveFields.matches(label)) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun ObjectFields(schema: AdminSchema, fields: List<AdminField>, value: JsonObject, onChange: (JsonObject) -> Unit, sensitive: Boolean, depth: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.filter { it.required || value[it.name] != null }.forEach { field ->
            Column {
                SchemaValueEditor(schema, field.type, value[field.name] ?: field.default ?: schema.defaultValue(field.type), { next -> onChange(JsonObject(value.toMutableMap().also { it[field.name] = next })) }, field.name, sensitive || SensitiveFields.matches(field.name), depth)
                if (!field.required) TextButton({ onChange(JsonObject(value.toMutableMap().also { it.remove(field.name) })) }) { Text("Omit ${field.name}") }
            }
        }
        val missing = fields.filter { !it.required && value[it.name] == null }
        if (missing.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }; Box { OutlinedButton({ open = true }) { Icon(Icons.Default.Add, null); Text("Add optional field") }; DropdownMenu(open, { open = false }) { missing.forEach { field -> DropdownMenuItem({ Text(field.name) }, { onChange(JsonObject(value + (field.name to (field.default ?: schema.defaultValue(field.type))))); open = false }) } } }
        }
    }
}

fun operationSeed(schema: AdminSchema, fields: List<AdminField>): JsonObject = buildJsonObject {
    fields.forEach { field -> if (field.required || field.hasDefault) put(field.name, field.default ?: schema.defaultValue(field.type)) }
}
