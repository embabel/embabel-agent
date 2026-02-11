/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.common.ai.converters

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.lang.reflect.Modifier
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap

class SchemaPruner(
    private val objectMapper: ObjectMapper,
    private val shouldKeepProperty: (propName: String, prop: BeanPropertyDefinition) -> Boolean,
    private val shouldKeepTopLevelPropertyName: (propName: String) -> Boolean = { true }
) {

    fun pruneAndSweep(rootJavaType: JavaType, schemaRoot: ObjectNode) {
        if (!validateSchemaOrFallback(schemaRoot)) return

        // 1) Prune properties (type-aware), resolving $ref as needed
        val pruneVisited = IdentityHashMap<JsonNode, Boolean>()
        pruneNodeForType(rootJavaType, schemaRoot, schemaRoot, pruneVisited, true)

        // 2) Mark & sweep $defs/definitions based on reachability
        sweepUnusedDefs(schemaRoot)
    }

    private fun validateSchemaOrFallback(schemaRoot: ObjectNode): Boolean {
        return try {
            validateSchema(schemaRoot)
            true
        } catch (e: RuntimeException) {
            logger.error(
                "Schema validation failed; falling back to top-level-only pruning: {}",
                e.message
            )
            pruneTopLevelPropertiesOnly(schemaRoot)
            false
        }
    }

    /**
     * Walk schema + JavaType in lockstep.
     *
     * @param rootSchema the actual schema root (used to resolve #/... pointers)
     * @param node current schema node
     */
    private fun pruneNodeForType(
        currentType: JavaType,
        rootSchema: ObjectNode,
        node: JsonNode,
        visited: MutableMap<JsonNode, Boolean>,
        isTopLevel: Boolean
    ) {
        // Prefer under-pruning over over-pruning for polymorphic base declarations.
        if (isPolymorphicBaseType(currentType)) return

        val resolved = resolveRefIfPresent(rootSchema, node)
        if (visited.put(resolved, true) != null) return

        // Handle schema composition wrappers
        handleCombinators(currentType, rootSchema, resolved, visited, isTopLevel)

        when {
            isObjectSchema(resolved) -> pruneObjectSchema(
                currentType = currentType,
                rootSchema = rootSchema,
                objSchema = resolved as ObjectNode,
                visited = visited,
                applyTopLevelPropertyNameFilter = isTopLevel
            )
            isArraySchema(resolved)  -> pruneArraySchema(currentType, rootSchema, resolved as ObjectNode, visited)
            isMapSchema(resolved)    -> pruneMapSchema(currentType, rootSchema, resolved as ObjectNode, visited)
            else -> {
                // primitives or unknown shapes: nothing to prune recursively
            }
        }
    }

    private fun pruneObjectSchema(
        currentType: JavaType,
        rootSchema: ObjectNode,
        objSchema: ObjectNode,
        visited: MutableMap<JsonNode, Boolean>,
        applyTopLevelPropertyNameFilter: Boolean
    ) {
        val propsNode = objSchema.get("properties") as? ObjectNode ?: return

        val propsByName = jacksonPropertiesByName(currentType)

        val it = propsNode.fieldNames() as MutableIterator<String>
        while (it.hasNext()) {
            val propName = it.next()
            if (applyTopLevelPropertyNameFilter && !shouldKeepTopLevelPropertyName(propName)) {
                it.remove()
                removeRequiredProperty(objSchema, propName)
                continue
            }
            val propDef = propsByName[propName]
            if (propDef == null) {
                if (isLikelyDiscriminatorMetadata(propsNode.get(propName))) {
                    continue
                }
                // Schema has a property Jackson doesn't see; safest is remove it.
                it.remove()
                removeRequiredProperty(objSchema, propName)
                continue
            }

            if (!shouldKeepProperty(propName, propDef)) {
                it.remove()
                removeRequiredProperty(objSchema, propName)
                continue
            }

            // Recurse into the property's schema node using property's JavaType
            val propSchemaNode = propsNode.get(propName)
            val propType = propDef.primaryTypeOrNull() ?: continue
            pruneNodeForType(propType, rootSchema, propSchemaNode, visited, false)
        }
    }

    private fun pruneArraySchema(
        currentType: JavaType,
        rootSchema: ObjectNode,
        arrSchema: ObjectNode,
        visited: MutableMap<JsonNode, Boolean>
    ) {
        val items = arrSchema.get("items") ?: return
        val contentType = currentType.contentType ?: return
        pruneNodeForType(contentType, rootSchema, items, visited, false)
    }

    private fun pruneMapSchema(
        currentType: JavaType,
        rootSchema: ObjectNode,
        mapSchema: ObjectNode,
        visited: MutableMap<JsonNode, Boolean>
    ) {
        val ap = mapSchema.get("additionalProperties") ?: return
        if (ap.isBoolean) return // additionalProperties: true/false
        val valueType = currentType.contentType ?: return
        pruneNodeForType(valueType, rootSchema, ap, visited, false)
    }

    private fun handleCombinators(
        currentType: JavaType,
        rootSchema: ObjectNode,
        node: JsonNode,
        visited: MutableMap<JsonNode, Boolean>,
        isTopLevel: Boolean
    ) {
        // oneOf/anyOf/allOf
        listOf("oneOf", "anyOf", "allOf").forEach { key ->
            (node.get(key) as? ArrayNode)?.forEach { child ->
                pruneNodeForType(currentType, rootSchema, child, visited, isTopLevel)
            }
        }

        // not
        node.get("not")?.let { child ->
            pruneNodeForType(currentType, rootSchema, child, visited, false)
        }

        // if/then/else
        node.get("if")?.let { pruneNodeForType(currentType, rootSchema, it, visited, false) }
        node.get("then")?.let { pruneNodeForType(currentType, rootSchema, it, visited, false) }
        node.get("else")?.let { pruneNodeForType(currentType, rootSchema, it, visited, false) }
    }

    private fun jacksonPropertiesByName(rootType: JavaType): Map<String, BeanPropertyDefinition> {
        val cfg = objectMapper.serializationConfig
        val beanDesc = cfg.introspect(rootType)
        return beanDesc.findProperties().associateBy { it.name }
    }

    private fun BeanPropertyDefinition.primaryTypeOrNull(): JavaType? {
        this.primaryMember?.let { return it.type }
        this.getter?.let { return it.type }
        this.field?.let { return it.type }
        this.constructorParameter?.let { return it.type }
        return null
    }

    /**
     * Resolve a local $ref (e.g. "#/$defs/Foo") to its target node.
     * If the node is not a $ref or not resolvable, returns the node as-is.
     */
    private fun resolveRefIfPresent(rootSchema: ObjectNode, node: JsonNode): JsonNode {
        val obj = node as? ObjectNode ?: return node
        val refNode = obj.get("\$ref")
        if (refNode != null && refNode.isTextual) {
            val ref = refNode.asText()
            if (ref.startsWith("#/")) {
                val pointer = ref.removePrefix("#")
                val target = rootSchema.at(pointer)
                if (!target.isMissingNode && !target.isNull) return target
            }
        }
        return node
    }

    private fun isObjectSchema(node: JsonNode): Boolean {
        if (node !is ObjectNode) return false
        val t = node.get("type")
        return (t?.isTextual == true && t.asText() == "object") || node.has("properties")
    }

    private fun isArraySchema(node: JsonNode): Boolean {
        if (node !is ObjectNode) return false
        val t = node.get("type")
        return (t?.isTextual == true && t.asText() == "array") || node.has("items")
    }

    private fun isMapSchema(node: JsonNode): Boolean {
        if (node !is ObjectNode) return false
        return node.has("additionalProperties")
    }

    /**
     * Mark & sweep all unused defs. Works with Draft 2020-12 ($defs) and older (definitions).
     */
    private fun sweepUnusedDefs(rootSchema: ObjectNode) {
        val defs = (rootSchema.get("\$defs") as? ObjectNode)
            ?: (rootSchema.get("definitions") as? ObjectNode)
            ?: return

        // Mark reachable definition keys
        val reachable = linkedSetOf<String>()
        val visited = IdentityHashMap<JsonNode, Boolean>()

        fun mark(node: JsonNode) {
            if (visited.put(node, true) != null) return

            val obj = node as? ObjectNode
            if (obj != null) {
                val refNode = obj.get("\$ref")
                if (refNode != null && refNode.isTextual) {
                    val ref = refNode.asText()
                    // expect "#/$defs/Name" or "#/definitions/Name"
                    val name = extractDefName(ref)
                    if (name != null && reachable.add(name)) {
                        val target = defs.get(name)
                        if (target != null) mark(target)
                    }
                }

                // traverse all children (covers properties/items/additionalProperties/combinators/etc.)
                val fields = obj.fields()
                while (fields.hasNext()) {
                    val (k, v) = fields.next()
                    // Optional micro-optimization: skip defs container itself
                    if (k == "\$defs" || k == "definitions") continue
                    mark(v)
                }
            } else if (node is ArrayNode) {
                node.forEach { mark(it) }
            }
        }

        // Mark from the root schema (excluding the defs container)
        mark(rootSchema)

        // Sweep: remove defs not reachable
        val defNames = defs.fieldNames() as MutableIterator<String>
        while (defNames.hasNext()) {
            val name = defNames.next()
            if (!reachable.contains(name)) defNames.remove()
        }

        // If empty, you can remove the container
        if (defs.size() == 0) {
            rootSchema.remove("\$defs")
            rootSchema.remove("definitions")
        }
    }

    private fun extractDefName(ref: String): String? {
        // "#/$defs/Foo" or "#/definitions/Foo"
        val p1 = "#/\$defs/"
        val p2 = "#/definitions/"
        return when {
            ref.startsWith(p1) -> ref.removePrefix(p1).takeIf { it.isNotBlank() }
            ref.startsWith(p2) -> ref.removePrefix(p2).takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun removeRequiredProperty(objSchema: ObjectNode, propertyName: String) {
        val required = objSchema.get("required") as? ArrayNode ?: return
        for (i in required.size() - 1 downTo 0) {
            val requiredName = required.get(i)
            if (requiredName.isTextual && requiredName.asText() == propertyName) {
                required.remove(i)
            }
        }
        if (required.size() == 0) {
            objSchema.remove("required")
        }
    }

    private fun pruneTopLevelPropertiesOnly(schemaRoot: ObjectNode) {
        val propsNode = schemaRoot.get("properties") as? ObjectNode ?: return
        val it = propsNode.fieldNames() as MutableIterator<String>
        while (it.hasNext()) {
            val name = it.next()
            if (shouldKeepTopLevelPropertyName(name)) continue
            it.remove()
            removeRequiredProperty(schemaRoot, name)
        }
    }

    private fun isLikelyDiscriminatorMetadata(node: JsonNode?): Boolean {
        val objNode = node as? ObjectNode ?: return false
        val constNode = objNode.get("const")
        if (constNode?.isTextual == true) return true
        val enumNode = objNode.get("enum") as? ArrayNode ?: return false
        return enumNode.size() == 1 && enumNode.get(0).isTextual
    }

    private fun isPolymorphicBaseType(currentType: JavaType): Boolean {
        val rawClass = currentType.rawClass
        if (rawClass.`package`?.name?.startsWith("java.") == true) return false
        if (Collection::class.java.isAssignableFrom(rawClass)) return false
        if (Map::class.java.isAssignableFrom(rawClass)) return false
        return rawClass.isInterface || Modifier.isAbstract(rawClass.modifiers)
    }

    private fun validateSchema(rootSchema: ObjectNode) {
        validateSchemaObject(rootSchema, "#")
    }

    private fun validateSchemaObject(schemaNode: ObjectNode, path: String) {
        val fields = schemaNode.fields()
        while (fields.hasNext()) {
            val (key, value) = fields.next()
            if (!KNOWN_SCHEMA_KEYWORDS.contains(key)) {
                throw IllegalStateException("Unexpected schema keyword '$key' at '$path'")
            }
            when (key) {
                "\$schema", "\$id", "\$ref", "\$anchor", "\$dynamicRef", "\$dynamicAnchor", "\$comment" ->
                    validateTextKeywordValue(value, "$path/$key")
                "\$vocabulary" ->
                    validateVocabulary(value, "$path/$key")
                "properties", "patternProperties", "\$defs", "definitions", "dependentSchemas" ->
                    validateSchemaObjectContainer(value, "$path/$key")
                "items" ->
                    validateSchemaNodeOrArray(value, "$path/$key")
                "additionalProperties", "unevaluatedProperties", "unevaluatedItems", "not", "if", "then", "else",
                "contains", "propertyNames", "contentSchema" ->
                    validateSchemaNodeOrBoolean(value, "$path/$key")
                "oneOf", "anyOf", "allOf", "prefixItems" ->
                    validateSchemaArray(value, "$path/$key")
                "dependentRequired" ->
                    validateDependentRequired(value, "$path/$key")
            }
        }
    }

    private fun validateSchemaObjectContainer(node: JsonNode, path: String) {
        val container = node as? ObjectNode
            ?: throw IllegalStateException("Expected object node at '$path', got ${node.nodeType}")
        val fields = container.fields()
        while (fields.hasNext()) {
            val (name, schemaValue) = fields.next()
            validateSchemaNodeOrBoolean(schemaValue, "$path/$name")
        }
    }

    private fun validateSchemaArray(node: JsonNode, path: String) {
        val arr = node as? ArrayNode
            ?: throw IllegalStateException("Expected array node at '$path', got ${node.nodeType}")
        for (i in 0 until arr.size()) {
            validateSchemaNodeOrBoolean(arr.get(i), "$path/$i")
        }
    }

    private fun validateSchemaNodeOrArray(node: JsonNode, path: String) {
        when (node) {
            is ArrayNode -> {
                for (i in 0 until node.size()) {
                    validateSchemaNodeOrBoolean(node.get(i), "$path/$i")
                }
            }
            else -> validateSchemaNodeOrBoolean(node, path)
        }
    }

    private fun validateSchemaNodeOrBoolean(node: JsonNode, path: String) {
        when {
            node is ObjectNode -> validateSchemaObject(node, path)
            node.isBoolean -> Unit // boolean-schema form: true/false
            else -> throw IllegalStateException("Expected schema object/boolean at '$path', got ${node.nodeType}")
        }
    }

    private fun validateDependentRequired(node: JsonNode, path: String) {
        val container = node as? ObjectNode
            ?: throw IllegalStateException("Expected object node at '$path', got ${node.nodeType}")
        val fields = container.fields()
        while (fields.hasNext()) {
            val (name, depsValue) = fields.next()
            val deps = depsValue as? ArrayNode
                ?: throw IllegalStateException("Expected array node at '$path/$name', got ${depsValue.nodeType}")
            for (i in 0 until deps.size()) {
                val dep = deps.get(i)
                if (!dep.isTextual) {
                    throw IllegalStateException("Expected string dependency at '$path/$name/$i', got ${dep.nodeType}")
                }
            }
        }
    }

    private fun validateTextKeywordValue(node: JsonNode, path: String) {
        if (!node.isTextual) {
            throw IllegalStateException("Expected string value at '$path', got ${node.nodeType}")
        }
    }

    private fun validateVocabulary(node: JsonNode, path: String) {
        val container = node as? ObjectNode
            ?: throw IllegalStateException("Expected object node at '$path', got ${node.nodeType}")
        val fields = container.fields()
        while (fields.hasNext()) {
            val (name, vocabEnabled) = fields.next()
            if (name.isBlank()) {
                throw IllegalStateException("Expected non-blank vocabulary URI key at '$path'")
            }
            if (!vocabEnabled.isBoolean) {
                throw IllegalStateException("Expected boolean vocabulary flag at '$path/$name', got ${vocabEnabled.nodeType}")
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SchemaPruner::class.java)

        private val KNOWN_SCHEMA_KEYWORDS = setOf(
            "\$schema", "\$id", "\$ref", "\$anchor", "\$dynamicRef", "\$dynamicAnchor", "\$vocabulary", "\$comment",
            "\$defs", "definitions",
            "type", "properties", "items", "additionalProperties",
            "oneOf", "anyOf", "allOf", "not", "if", "then", "else",
            "required", "enum", "const",
            "title", "description", "format", "default", "examples",
            "deprecated", "readOnly", "writeOnly",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "pattern",
            "minItems", "maxItems", "uniqueItems", "contains", "minContains", "maxContains",
            "minProperties", "maxProperties",
            "prefixItems", "unevaluatedItems", "unevaluatedProperties",
            "patternProperties", "propertyNames",
            "dependentRequired", "dependentSchemas",
            "contentEncoding", "contentMediaType", "contentSchema"
        )
    }
}
