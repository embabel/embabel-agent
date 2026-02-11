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

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilteringJacksonOutputConverterTest {

    private val objectMapper = jacksonObjectMapper()

    @kotlin.annotation.Target(AnnotationTarget.FIELD)
    @kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
    annotation class SkipA

    @kotlin.annotation.Target(AnnotationTarget.FIELD)
    @kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
    annotation class SkipB

    @kotlin.annotation.Target(AnnotationTarget.FIELD)
    @kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
    annotation class SkipC

    data class Person(
        val name: String,
        val age: Int,
        val email: String,
        val address: String
    )

    data class Password(
        @field:SkipPropertyFilter
        val passwordString: String,
        val hash: String
    )

    data class SomePerson(
        val name: String,
        val age: Int,
        @field:SkipPropertyFilter
        val email: String,
        @field:SkipPropertyFilter
        val address: String,
        val password: Password
    )

    @Test
    fun `test schema should skip deeply nested and preserve expected metadata`() {
        val skipFilter = JacksonPropertyFilter.SkipAnnotation(SkipPropertyFilter::class.java)

        val graphSchema = parseSchema(
            FilteringJacksonOutputConverter<Graph>(
                clazz = Graph::class.java,
                objectMapper = objectMapper,
                propertyFilter = skipFilter
            ).jsonSchema
        )
        val graphProperties = allPropertyNames(graphSchema)

        assertTrue(graphProperties.containsAll(setOf("root", "edges", "to", "attrs")))
        assertFalse(graphProperties.contains("id"))
        assertFalse(graphProperties.contains("label"))

        val ruleSchema = parseSchema(
            FilteringJacksonOutputConverter<Rule>(
                clazz = Rule::class.java,
                objectMapper = objectMapper,
                propertyFilter = skipFilter
            ).jsonSchema
        )
        val ruleProperties = allPropertyNames(ruleSchema)

        assertTrue(ruleProperties.containsAll(setOf("enabled", "condition", "operands", "actions", "id", "effect")))
        assertFalse(ruleProperties.contains("expr"))
    }

    @Test
    fun `test schema should skip annotations recursively`() {
        val schema = parseSchema(
            FilteringJacksonOutputConverter<SomePerson>(
                clazz = SomePerson::class.java,
                objectMapper = objectMapper,
                propertyFilter = JacksonPropertyFilter.SkipAnnotation(SkipPropertyFilter::class.java)
            ).jsonSchema
        )
        val propertyNames = allPropertyNames(schema)

        assertTrue(propertyNames.containsAll(setOf("name", "age", "password", "hash")))
        assertFalse(propertyNames.contains("email"))
        assertFalse(propertyNames.contains("address"))
        assertFalse(propertyNames.contains("passwordString"))
    }

    @Test
    fun `test schema should include only specified properties`() {
        val schema = parseSchema(
            FilteringJacksonOutputConverter<Person>(
                clazz = Person::class.java,
                objectMapper = objectMapper,
                propertyFilter = JacksonPropertyFilter.MatchesPropertyValue({ it == "name" || it == "age" })
            ).jsonSchema
        )

        assertEquals(setOf("name", "age"), rootPropertyNames(schema))
    }

    @Test
    fun `test schema should exclude specified properties`() {
        val schema = parseSchema(
            FilteringJacksonOutputConverter<Person>(
                clazz = Person::class.java,
                objectMapper = objectMapper,
                propertyFilter = JacksonPropertyFilter.MatchesPropertyValue({ it != "email" && it != "address" })
            ).jsonSchema
        )

        assertEquals(setOf("name", "age"), rootPropertyNames(schema))
    }

    @Test
    fun `schema validation failure should fallback to top-level pruning`() {
        val filter = JacksonPropertyFilter
            .SkipAnnotation(SkipPropertyFilter::class.java)
            .and(JacksonPropertyFilter.property { it == "name" })
        val converter = SchemaMutatingConverter(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = filter
        )

        val schema = assertDoesNotThrow<String> {
            converter.jsonSchema
        }
        val root = parseSchema(schema)
        assertEquals(setOf("name"), rootPropertyNames(root))
    }

    @Test
    fun `top-level-only pruning should skip schema validation`() {
        val converter = SchemaMutatingConverter(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = JacksonPropertyFilter.MatchesPropertyValue { it == "name" }
        )

        val schema = assertDoesNotThrow<String> {
            converter.jsonSchema
        }
        val root = parseSchema(schema)
        assertEquals(setOf("name"), rootPropertyNames(root))
    }

    @Test
    fun `schema validation should allow draft 2020 core keywords`() {
        val converter = CoreKeywordsMutatingConverter(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = JacksonPropertyFilter.SkipAnnotation(SkipPropertyFilter::class.java)
        )

        val schema = assertDoesNotThrow<String> {
            converter.jsonSchema
        }
        val root = parseSchema(schema)
        assertTrue(root.has("\$anchor"))
        assertTrue(root.has("\$dynamicRef"))
        assertTrue(root.has("\$dynamicAnchor"))
        assertTrue(root.has("\$vocabulary"))
        assertTrue(root.has("\$comment"))
    }

    @Test
    fun `invalid vocabulary should fallback to top-level pruning`() {
        val filter = JacksonPropertyFilter
            .SkipAnnotation(SkipPropertyFilter::class.java)
            .and(JacksonPropertyFilter.property { it == "name" })
        val converter = InvalidVocabularyMutatingConverter(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = filter
        )

        val schema = assertDoesNotThrow<String> {
            converter.jsonSchema
        }
        val root = parseSchema(schema)
        assertEquals(setOf("name"), rootPropertyNames(root))
    }

    @Test
    fun `skip annotations should be conditional per annotation type`() {
        val skipA = JacksonPropertyFilter.SkipAnnotation(SkipA::class.java)
        val skipB = JacksonPropertyFilter.SkipAnnotation(SkipB::class.java)
        val skipC = JacksonPropertyFilter.SkipAnnotation(SkipC::class.java)

        val contactA = rootPropertyNames(parseSchema(schemaForClass(Contact::class.java, skipA)))
        val contactB = rootPropertyNames(parseSchema(schemaForClass(Contact::class.java, skipB)))
        assertFalse(contactA.contains("address"))
        assertTrue(contactB.contains("address"))

        val notificationA = rootPropertyNames(parseSchema(schemaForClass(NotificationPolicy::class.java, skipA)))
        val notificationB = rootPropertyNames(parseSchema(schemaForClass(NotificationPolicy::class.java, skipB)))
        assertTrue(notificationA.contains("rules"))
        assertFalse(notificationB.contains("rules"))

        val targetB = rootPropertyNames(parseSchema(schemaForClass(Target::class.java, skipB)))
        val targetC = rootPropertyNames(parseSchema(schemaForClass(Target::class.java, skipC)))
        assertTrue(targetB.contains("destination"))
        assertFalse(targetC.contains("destination"))

        val metaA = rootPropertyNames(parseSchema(schemaForClass(MetaString::class.java, skipA)))
        val metaC = rootPropertyNames(parseSchema(schemaForClass(MetaString::class.java, skipC)))
        assertFalse(metaA.contains("v"))
        assertTrue(metaC.contains("v"))

        val attrA = rootPropertyNames(parseSchema(schemaForClass(AttrString::class.java, skipA)))
        val attrC = rootPropertyNames(parseSchema(schemaForClass(AttrString::class.java, skipC)))
        assertTrue(attrA.contains("v"))
        assertFalse(attrC.contains("v"))
    }

    @Test
    fun `combined annotation filters should apply union of skips`() {
        val filter = JacksonPropertyFilter
            .SkipAnnotation(SkipA::class.java)
            .and(JacksonPropertyFilter.SkipAnnotation(SkipB::class.java))

        val notificationPolicyProps = rootPropertyNames(parseSchema(schemaForClass(NotificationPolicy::class.java, filter)))
        assertFalse(notificationPolicyProps.contains("rules"))

        val contactProps = rootPropertyNames(parseSchema(schemaForClass(Contact::class.java, filter)))
        assertFalse(contactProps.contains("address"))

        val actionProps = rootPropertyNames(parseSchema(schemaForClass(Action::class.java, filter)))
        assertFalse(actionProps.contains("id"))
    }

    @Test
    fun `top level predicate should only prune root properties`() {
        val schema = parseSchema(
            FilteringJacksonOutputConverter<Root>(
                clazz = Root::class.java,
                objectMapper = objectMapper,
                propertyFilter = JacksonPropertyFilter.MatchesPropertyValue {
                    it == "profile"
                }
            ).jsonSchema
        )

        assertEquals(setOf("profile"), rootPropertyNames(schema))

        // Nested names are still present because name-predicate pruning is top-level only.
        val nestedPropertyNames = allPropertyNames(schema)
        assertTrue(nestedPropertyNames.contains("name"))
        assertTrue(nestedPropertyNames.contains("contacts"))
        assertTrue(nestedPropertyNames.contains("notifications"))
    }

    @Test
    fun `recursive pruning should remove dangling refs and unreachable defs`() {
        val filter = JacksonPropertyFilter
            .SkipAnnotation(SkipPropertyFilter::class.java)
            .and(JacksonPropertyFilter.property { it == "profile" })
        val schema = parseSchema(
            FilteringJacksonOutputConverter<Root>(
                clazz = Root::class.java,
                objectMapper = objectMapper,
                propertyFilter = filter
            ).jsonSchema
        )

        assertEquals(setOf("profile"), rootPropertyNames(schema))
        val defs = defNames(schema)
        assertFalse(defs.contains("Session"))
        assertFalse(defs.contains("OrgSettings"))

        val refTargets = refTargets(schema)
        assertTrue(refTargets.all { defs.contains(it) })
    }

    @Test
    fun `top-level-only pruning should not sweep defs`() {
        val schema = parseSchema(
            FilteringJacksonOutputConverter<Root>(
                clazz = Root::class.java,
                objectMapper = objectMapper,
                propertyFilter = JacksonPropertyFilter.MatchesPropertyValue { false }
            ).jsonSchema
        )

        assertTrue(rootPropertyNames(schema).isEmpty())
        assertTrue(schema.has("\$defs") || schema.has("definitions"))
        val defs = defNames(schema)
        val refs = refTargets(schema)
        assertTrue(refs.all { defs.contains(it) })
    }

    @Test
    fun `polymorphic collections should under prune subtype fields`() {
        val allowAll = JacksonPropertyFilter.allowAll()
        val filter = JacksonPropertyFilter
            .SkipAnnotation(SkipA::class.java)
            .and(JacksonPropertyFilter.SkipAnnotation(SkipC::class.java))

        val baselineSchema = parseSchema(schemaForClass(PolymorphicCollections::class.java, allowAll))
        val schema = parseSchema(schemaForClass(PolymorphicCollections::class.java, filter))

        assertTrue(rootPropertyNames(schema).containsAll(setOf("transports", "effectsById", "transportBatches")))
        assertEquals(allPropertyNames(baselineSchema), allPropertyNames(schema))
        assertEquals(defNames(baselineSchema), defNames(schema))

        val defs = defNames(schema)
        val refs = refTargets(schema)
        assertTrue(refs.all { defs.contains(it) })
    }

    @Test
    fun `skipping one polymorphic reference should not prune shared polymorphic refs`() {
        val allowAll = JacksonPropertyFilter.allowAll()
        val filter = JacksonPropertyFilter
            .SkipAnnotation(SkipA::class.java)
            .and(JacksonPropertyFilter.SkipAnnotation(SkipC::class.java))

        val baselineSchema = parseSchema(schemaForClass(PolymorphicSharedRefs::class.java, allowAll))
        val schema = parseSchema(schemaForClass(PolymorphicSharedRefs::class.java, filter))
        val rootProps = rootPropertyNames(schema)
        val baselineRootProps = rootPropertyNames(baselineSchema)

        assertFalse(rootProps.contains("skippedTransport"))
        assertTrue(rootProps.contains("keptTransport"))
        assertTrue(rootProps.contains("effects"))
        assertTrue(baselineRootProps.contains("skippedTransport"))

        // Pruning one polymorphic reference should not change nested polymorphic shape.
        val expectedNames = allPropertyNames(baselineSchema) - "skippedTransport"
        assertEquals(expectedNames, allPropertyNames(schema))
        assertEquals(defNames(baselineSchema), defNames(schema))

        val defs = defNames(schema)
        val refs = refTargets(schema)
        assertTrue(refs.isNotEmpty())
        assertTrue(refs.all { defs.contains(it) })
    }

    @Test
    fun `abstract polymorphic bases should under prune subtype fields`() {
        val allowAll = JacksonPropertyFilter.allowAll()
        val filter = JacksonPropertyFilter
            .SkipAnnotation(SkipA::class.java)
            .and(JacksonPropertyFilter.SkipAnnotation(SkipC::class.java))

        val baselineSchema = parseSchema(schemaForClass(AbstractPolymorphicRefs::class.java, allowAll))
        val schema = parseSchema(schemaForClass(AbstractPolymorphicRefs::class.java, filter))
        val rootProps = rootPropertyNames(schema)

        assertFalse(rootProps.contains("skippedShape"))
        assertTrue(rootProps.containsAll(setOf("keptShape", "shapes", "shapeMap")))

        // Skip-annotated fields inside abstract polymorphic branches are kept by design (under-prune).
        val expectedNames = allPropertyNames(baselineSchema) - "skippedShape"
        assertEquals(expectedNames, allPropertyNames(schema))
        assertEquals(defNames(baselineSchema), defNames(schema))

        val defs = defNames(schema)
        val refs = refTargets(schema)
        assertTrue(refs.isNotEmpty())
        assertTrue(refs.all { defs.contains(it) })
    }

    @Test
    fun `polymorphic list should keep tag effect fields under skip annotation`() {
        val allowAll = JacksonPropertyFilter.allowAll()
        val skipC = JacksonPropertyFilter.SkipAnnotation(SkipC::class.java)

        val baselineSchema = parseSchema(schemaForClass(EffectListContainer::class.java, allowAll))
        val schema = parseSchema(schemaForClass(EffectListContainer::class.java, skipC))

        val baselineNames = allPropertyNames(baselineSchema)
        val filteredNames = allPropertyNames(schema)
        assertTrue(baselineNames.contains("tags"))
        assertTrue(filteredNames.contains("tags"))
        assertEquals(baselineNames, filteredNames)
        assertEquals(defNames(baselineSchema), defNames(schema))
    }

    @Test
    fun `polymorphic map should keep tag effect fields under skip annotation`() {
        val allowAll = JacksonPropertyFilter.allowAll()
        val skipC = JacksonPropertyFilter.SkipAnnotation(SkipC::class.java)

        val baselineSchema = parseSchema(schemaForClass(EffectMapContainer::class.java, allowAll))
        val schema = parseSchema(schemaForClass(EffectMapContainer::class.java, skipC))

        assertTrue(rootPropertyNames(schema).contains("effectsById"))
        val baselineNames = allPropertyNames(baselineSchema)
        val filteredNames = allPropertyNames(schema)
        // For map value schemas, generator currently omits concrete subtype fields like "tags".
        assertFalse(baselineNames.contains("tags"))
        assertFalse(filteredNames.contains("tags"))
        assertEquals(baselineNames, filteredNames)
        assertEquals(defNames(baselineSchema), defNames(schema))
    }

    @Test
    fun `polymorphic map of list should keep tag effect fields under skip annotation`() {
        val allowAll = JacksonPropertyFilter.allowAll()
        val skipC = JacksonPropertyFilter.SkipAnnotation(SkipC::class.java)

        val baselineSchema = parseSchema(schemaForClass(EffectMapOfListContainer::class.java, allowAll))
        val schema = parseSchema(schemaForClass(EffectMapOfListContainer::class.java, skipC))

        assertTrue(rootPropertyNames(schema).contains("effectsByGroup"))
        val baselineNames = allPropertyNames(baselineSchema)
        val filteredNames = allPropertyNames(schema)
        // For map/list-map value schemas, generator currently omits concrete subtype fields like "tags".
        assertFalse(baselineNames.contains("tags"))
        assertFalse(filteredNames.contains("tags"))
        assertEquals(baselineNames, filteredNames)
        assertEquals(defNames(baselineSchema), defNames(schema))
    }

    @Test
    fun `concrete effect implementation should still be pruned by skip annotation`() {
        val skipC = JacksonPropertyFilter.SkipAnnotation(SkipC::class.java)

        val tagEffectSchema = parseSchema(schemaForClass(TagEffect::class.java, skipC))
        val tagEffectNames = rootPropertyNames(tagEffectSchema)
        assertFalse(tagEffectNames.contains("tags"))
    }

    @Test
    fun `should generate schema for every listed type`() {
        val filter = JacksonPropertyFilter.SkipAnnotation(SkipPropertyFilter::class.java)
        val allTypes = listOf(
            Root::class.java,
            Profile::class.java,
            Name::class.java,
            Contact::class.java,
            Address::class.java,
            Geo::class.java,
            Preferences::class.java,
            NotificationPolicy::class.java,
            Channel::class.java,
            Transport::class.java,
            EmailTransport::class.java,
            SmsTransport::class.java,
            WebhookTransport::class.java,
            Rule::class.java,
            Condition::class.java,
            Operand::class.java,
            Action::class.java,
            Effect::class.java,
            TagEffect::class.java,
            ForwardEffect::class.java,
            Target::class.java,
            Session::class.java,
            Timeline::class.java,
            Node::class.java,
            MetaValue::class.java,
            MetaString::class.java,
            MetaNumber::class.java,
            MetaObject::class.java,
            OrgSettings::class.java,
            OrgPolicy::class.java,
            RetentionPolicy::class.java,
            ArchiveTarget::class.java,
            Graph::class.java,
            GNode::class.java,
            Edge::class.java,
            AttrValue::class.java,
            AttrString::class.java,
            AttrInt::class.java,
            AttrObj::class.java,
            PolymorphicCollections::class.java,
            PolymorphicSharedRefs::class.java,
            Shape::class.java,
            Circle::class.java,
            Rectangle::class.java,
            AbstractPolymorphicRefs::class.java,
            EffectListContainer::class.java,
            EffectMapContainer::class.java,
            EffectMapOfListContainer::class.java
        )

        allTypes.forEach { clazz ->
            assertDoesNotThrow {
                schemaForClass(clazz, filter)
            }
        }
    }

    private fun rootPropertyNames(schema: JsonNode): Set<String> {
        val propertiesNode = schema.get("properties") as? ObjectNode ?: return emptySet()
        val names = linkedSetOf<String>()
        val iterator = propertiesNode.fieldNames()
        while (iterator.hasNext()) {
            names.add(iterator.next())
        }
        return names
    }

    private fun allPropertyNames(schema: JsonNode): Set<String> {
        val names = linkedSetOf<String>()
        fun collect(node: JsonNode) {
            when (node) {
                is ObjectNode -> {
                    val propertiesNode = node.get("properties") as? ObjectNode
                    if (propertiesNode != null) {
                        val iterator = propertiesNode.fieldNames()
                        while (iterator.hasNext()) {
                            names.add(iterator.next())
                        }
                    }
                    val fields = node.fields()
                    while (fields.hasNext()) {
                        collect(fields.next().value)
                    }
                }
                is ArrayNode -> node.forEach(::collect)
            }
        }
        collect(schema)
        return names
    }

    private fun parseSchema(schema: String): ObjectNode {
        return objectMapper.readTree(schema) as ObjectNode
    }

    private fun schemaForClass(
        clazz: Class<*>,
        propertyFilter: JacksonPropertyFilter
    ): String {
        @Suppress("UNCHECKED_CAST")
        val converter = FilteringJacksonOutputConverter(
            clazz = clazz as Class<Any>,
            objectMapper = objectMapper,
            propertyFilter = propertyFilter
        )
        return converter.jsonSchema
    }

    private fun defNames(schema: ObjectNode): Set<String> {
        val defs = (schema.get("\$defs") as? ObjectNode)
            ?: (schema.get("definitions") as? ObjectNode)
            ?: return emptySet()
        val names = linkedSetOf<String>()
        val iterator = defs.fieldNames()
        while (iterator.hasNext()) {
            names.add(iterator.next())
        }
        return names
    }

    private fun refTargets(schema: JsonNode): Set<String> {
        val targets = linkedSetOf<String>()
        fun collect(node: JsonNode) {
            when (node) {
                is ObjectNode -> {
                    val refNode = node.get("\$ref")
                    if (refNode?.isTextual == true) {
                        extractDefName(refNode.asText())?.let { targets.add(it) }
                    }
                    val fields = node.fields()
                    while (fields.hasNext()) {
                        collect(fields.next().value)
                    }
                }
                is ArrayNode -> node.forEach(::collect)
            }
        }
        collect(schema)
        return targets
    }

    private fun extractDefName(ref: String): String? {
        val defsPrefix = "#/\$defs/"
        val definitionsPrefix = "#/definitions/"
        return when {
            ref.startsWith(defsPrefix) -> ref.removePrefix(defsPrefix)
            ref.startsWith(definitionsPrefix) -> ref.removePrefix(definitionsPrefix)
            else -> null
        }.takeIf { !it.isNullOrBlank() }
    }

    private class SchemaMutatingConverter<T>(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        propertyFilter: JacksonPropertyFilter
    ) : FilteringJacksonOutputConverter<T>(clazz, objectMapper, propertyFilter) {
        override fun postProcessSchema(jsonNode: JsonNode) {
            val root = jsonNode as? ObjectNode ?: return
            val propertiesNode = root.get("properties") as? ObjectNode ?: root.putObject("properties")
            val emailNode = propertiesNode.get("email") as? ObjectNode ?: propertiesNode.putObject("email")
            emailNode.put("unexpectedSchemaKeyword", "boom")
            super.postProcessSchema(root)
        }
    }

    private class CoreKeywordsMutatingConverter<T>(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        propertyFilter: JacksonPropertyFilter
    ) : FilteringJacksonOutputConverter<T>(clazz, objectMapper, propertyFilter) {
        override fun postProcessSchema(jsonNode: JsonNode) {
            val root = jsonNode as? ObjectNode ?: return
            root.put("\$anchor", "rootAnchor")
            root.put("\$dynamicRef", "#/\$defs/Any")
            root.put("\$dynamicAnchor", "dynamicAnchor")
            root.put("\$comment", "schema comment")
            val vocabulary = root.putObject("\$vocabulary")
            vocabulary.put("https://json-schema.org/draft/2020-12/vocab/core", true)
            super.postProcessSchema(root)
        }
    }

    private class InvalidVocabularyMutatingConverter<T>(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        propertyFilter: JacksonPropertyFilter
    ) : FilteringJacksonOutputConverter<T>(clazz, objectMapper, propertyFilter) {
        override fun postProcessSchema(jsonNode: JsonNode) {
            val root = jsonNode as? ObjectNode ?: return
            val vocabulary = root.putObject("\$vocabulary")
            vocabulary.put("https://json-schema.org/draft/2020-12/vocab/core", "true")
            super.postProcessSchema(root)
        }
    }

    data class Root(
        val id: String,
        val profile: Profile,
        val sessions: List<Session>,
        val settingsByOrg: Map<String, OrgSettings>,
    )

    data class Profile(
        val name: Name,
        val contacts: List<Contact>,
        val preferences: Preferences,
    )

    data class Name(
        val first: String,
        val last: String,
    )

    data class Contact(
        val kind: ContactKind,
        @field:SkipA
        val address: Address?,
    )

    enum class ContactKind { EMAIL, PHONE, POSTAL }

    data class Address(
        val lines: List<String>,
        val geo: Geo?,
    )

    data class Geo(
        val lat: Double,
        val lon: Double,
    )

    data class Preferences(
        val locale: String,
        val notifications: NotificationPolicy,
    )

    data class NotificationPolicy(
        val channels: List<Channel>,
        @field:SkipB
        val rules: Map<String, Rule>,
    )

    data class Channel(
        val id: String,
        val transport: Transport,
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes(
        JsonSubTypes.Type(value = EmailTransport::class, name = "email"),
        JsonSubTypes.Type(value = SmsTransport::class, name = "sms"),
        JsonSubTypes.Type(value = WebhookTransport::class, name = "webhook"),
    )
    sealed interface Transport

    data class EmailTransport(
        val from: String,
        @field:SkipA
        val replyTo: String?
    ) : Transport

    data class SmsTransport(
        val provider: String,
        @field:SkipA
        val senderId: String?
    ) : Transport

    data class WebhookTransport(
        val url: String,
        @field:SkipA
        val headers: Map<String, String>
    ) : Transport

    data class Rule(
        val enabled: Boolean,
        val condition: Condition,
        val actions: List<Action>,
    )

    data class Condition(
        @field:SkipPropertyFilter
        val expr: String,
        val operands: List<Operand>,
    )

    data class Operand(
        val key: String,
        @field:SkipB
        val value: String,
    )

    data class Action(
        @field:SkipB
        val id: String,
        val effect: Effect,
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(value = TagEffect::class, name = "tag"),
        JsonSubTypes.Type(value = ForwardEffect::class, name = "forward"),
    )
    sealed interface Effect

    data class TagEffect(
        @field:SkipC
        val tags: Set<String>
    ) : Effect

    data class ForwardEffect(
        @field:SkipC
        val target: Target
    ) : Effect

    data class Target(
        val type: TargetType,
        @field:SkipC
        val destination: String,
    )

    enum class TargetType { EMAIL, WEBHOOK }

    data class Session(
        @field:SkipPropertyFilter
        val sessionId: String,
        val timeline: Timeline,
    )

    data class Timeline(
        val head: Node
    )

    /**
     * Recursive type -> strongly encourages $defs/$ref usage.
     */
    data class Node(
        @field:SkipPropertyFilter
        val name: String,
        val children: List<Node> = emptyList(),
        val metadata: Map<String, MetaValue> = emptyMap(),
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
    @JsonSubTypes(
        JsonSubTypes.Type(value = MetaString::class, name = "s"),
        JsonSubTypes.Type(value = MetaNumber::class, name = "n"),
        JsonSubTypes.Type(value = MetaObject::class, name = "o"),
    )
    sealed interface MetaValue

    data class MetaString(@field:SkipA val v: String) : MetaValue
    data class MetaNumber(@field:SkipA val v: Double) : MetaValue
    data class MetaObject(@field:SkipA val v: Map<String, MetaValue>) : MetaValue

    data class OrgSettings(
        @field:SkipB
        val orgId: String,
        val policy: OrgPolicy
    )

    data class OrgPolicy(
        @field:SkipB
        val maxSessions: Int,
        val retention: RetentionPolicy
    )

    data class RetentionPolicy(
        @field:SkipB
        val days: Int,
        val archive: ArchiveTarget
    )

    data class ArchiveTarget(
        @field:SkipPropertyFilter
        val bucket: String,
        val pathPrefix: String
    )

    data class PolymorphicCollections(
        val transports: List<Transport>,
        val effectsById: Map<String, Effect>,
        val transportBatches: Map<String, List<Transport>>
    )

    data class PolymorphicSharedRefs(
        @field:SkipA
        val skippedTransport: Transport,
        val keptTransport: Transport,
        val effects: List<Effect>
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "shapeType")
    @JsonSubTypes(
        JsonSubTypes.Type(value = Circle::class, name = "circle"),
        JsonSubTypes.Type(value = Rectangle::class, name = "rectangle"),
    )
    sealed class Shape

    data class Circle(
        @field:SkipA
        val radius: Double,
        val unit: String
    ) : Shape()

    data class Rectangle(
        @field:SkipC
        val width: Double,
        val height: Double
    ) : Shape()

    data class AbstractPolymorphicRefs(
        @field:SkipA
        val skippedShape: Shape,
        val keptShape: Shape,
        val shapes: List<Shape>,
        val shapeMap: Map<String, Shape>
    )

    data class EffectListContainer(
        val effects: List<Effect>
    )

    data class EffectMapContainer(
        val effectsById: Map<String, Effect>
    )

    data class EffectMapOfListContainer(
        val effectsByGroup: Map<String, List<Effect>>
    )

    data class Graph(
        val root: GNode
    )

    data class GNode(
        @field:SkipPropertyFilter
        val id: String,
        val edges: List<Edge>,
        val attrs: Map<String, AttrValue>,
    )

    data class Edge(
        @field:SkipPropertyFilter
        val label: String,
        val to: GNode  // recursion through a different path than "children"
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "k")
    @JsonSubTypes(
        JsonSubTypes.Type(value = AttrString::class, name = "str"),
        JsonSubTypes.Type(value = AttrInt::class, name = "int"),
        JsonSubTypes.Type(value = AttrObj::class, name = "obj"),
    )
    sealed interface AttrValue

    data class AttrString(@field:SkipC val v: String) : AttrValue
    data class AttrInt(@field:SkipC val v: Int) : AttrValue
    data class AttrObj(@field:SkipC val v: Map<String, AttrValue>) : AttrValue


}
