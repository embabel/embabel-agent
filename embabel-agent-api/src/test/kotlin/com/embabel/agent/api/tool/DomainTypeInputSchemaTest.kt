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
package com.embabel.agent.api.tool

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.DomainTypePropertyDefinition
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValuePropertyDefinition
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [DomainTypeInputSchema].
 */
class DomainTypeInputSchemaTest {

    private val objectMapper = jacksonObjectMapper()

    private fun createSimpleDomainType(
        name: String = "TestType",
        properties: List<ValuePropertyDefinition> = emptyList(),
    ): DomainType = DynamicType(
        name = name,
        description = "Test domain type",
        ownProperties = properties,
    )

    @Nested
    inner class FactoryMethodsTest {

        @Test
        fun `of creates schema from DomainType`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("name", "string"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)

            assertNotNull(schema)
            assertTrue(schema.parameters.isNotEmpty())
        }
    }

    @Nested
    inner class ValueTypesMappingTest {

        @Test
        fun `maps string type correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("name", "string"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "name" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.STRING, param!!.type)
        }

        @Test
        fun `maps int type correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("age", "int"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "age" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.INTEGER, param!!.type)
        }

        @Test
        fun `maps integer type correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("count", "integer"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "count" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.INTEGER, param!!.type)
        }

        @Test
        fun `maps long type correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("bigNumber", "long"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "bigNumber" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.INTEGER, param!!.type)
        }

        @Test
        fun `maps double type correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("price", "double"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "price" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.NUMBER, param!!.type)
        }

        @Test
        fun `maps float type correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("rating", "float"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "rating" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.NUMBER, param!!.type)
        }

        @Test
        fun `maps number type correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("value", "number"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "value" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.NUMBER, param!!.type)
        }

        @Test
        fun `maps boolean type correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("active", "boolean"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "active" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.BOOLEAN, param!!.type)
        }

        @Test
        fun `maps unknown type to string`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("custom", "customType"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "custom" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.STRING, param!!.type)
        }
    }

    @Nested
    inner class CardinalityMappingTest {

        @Test
        fun `ONE cardinality is required`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("name", "string", Cardinality.ONE),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "name" }

            assertNotNull(param)
            assertTrue(param!!.required)
            assertEquals(Tool.ParameterType.STRING, param.type)
        }

        @Test
        fun `OPTIONAL cardinality is not required`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("nickname", "string", Cardinality.OPTIONAL),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "nickname" }

            assertNotNull(param)
            assertFalse(param!!.required)
        }

        @Test
        fun `LIST cardinality becomes array type`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("tags", "string", Cardinality.LIST),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "tags" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.ARRAY, param!!.type)
            assertEquals(Tool.ParameterType.STRING, param.itemType)
            assertTrue(param.required)
        }

        @Test
        fun `SET cardinality becomes array type`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("categories", "string", Cardinality.SET),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "categories" }

            assertNotNull(param)
            assertEquals(Tool.ParameterType.ARRAY, param!!.type)
            assertEquals(Tool.ParameterType.STRING, param.itemType)
        }
    }

    @Nested
    inner class NestedDomainTypeTest {

        @Test
        fun `nested DomainType becomes object type`() {
            val addressType = DynamicType(
                name = "Address",
                description = "Address type",
                ownProperties = listOf(
                    ValuePropertyDefinition("street", "string"),
                    ValuePropertyDefinition("city", "string"),
                ),
            )

            val personType = DynamicType(
                name = "Person",
                description = "Person type",
                ownProperties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    DomainTypePropertyDefinition("address", addressType),
                ),
            )

            val schema = Tool.InputSchema.of(personType)

            val nameParam = schema.parameters.find { it.name == "name" }
            assertNotNull(nameParam)
            assertEquals(Tool.ParameterType.STRING, nameParam!!.type)

            val addressParam = schema.parameters.find { it.name == "address" }
            assertNotNull(addressParam)
            assertEquals(Tool.ParameterType.OBJECT, addressParam!!.type)
            assertNotNull(addressParam.properties)
            assertEquals(2, addressParam.properties!!.size)

            val streetProp = addressParam.properties!!.find { it.name == "street" }
            assertNotNull(streetProp)
            assertEquals(Tool.ParameterType.STRING, streetProp!!.type)
        }

        @Test
        fun `nested DomainType with LIST cardinality becomes array of objects`() {
            val itemType = DynamicType(
                name = "Item",
                description = "Item type",
                ownProperties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    ValuePropertyDefinition("quantity", "int"),
                ),
            )

            val orderType = DynamicType(
                name = "Order",
                description = "Order type",
                ownProperties = listOf(
                    ValuePropertyDefinition("orderId", "string"),
                    DomainTypePropertyDefinition("items", itemType, Cardinality.LIST),
                ),
            )

            val schema = Tool.InputSchema.of(orderType)

            val itemsParam = schema.parameters.find { it.name == "items" }
            assertNotNull(itemsParam)
            assertEquals(Tool.ParameterType.ARRAY, itemsParam!!.type)
            assertNotNull(itemsParam.properties)
        }
    }

    @Nested
    inner class DescriptionTest {

        @Test
        fun `uses property description`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("email", "string", description = "User email address"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val param = schema.parameters.find { it.name == "email" }

            assertNotNull(param)
            assertEquals("User email address", param!!.description)
        }
    }

    @Nested
    inner class JsonSchemaTest {

        @Test
        fun `toJsonSchema returns valid JSON`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    ValuePropertyDefinition("age", "int"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val jsonSchema = schema.toJsonSchema()

            val parsed = objectMapper.readTree(jsonSchema)
            assertNotNull(parsed)
        }

        @Test
        fun `toJsonSchema has type object`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("name", "string"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)

            assertEquals("object", parsed.get("type").asText())
        }

        @Test
        fun `toJsonSchema has properties for each field`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    ValuePropertyDefinition("age", "int"),
                    ValuePropertyDefinition("active", "boolean"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val properties = parsed.get("properties")

            assertTrue(properties.has("name"))
            assertTrue(properties.has("age"))
            assertTrue(properties.has("active"))
        }

        @Test
        fun `toJsonSchema maps types correctly`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    ValuePropertyDefinition("count", "int"),
                    ValuePropertyDefinition("price", "double"),
                    ValuePropertyDefinition("active", "boolean"),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val properties = parsed.get("properties")

            assertEquals("string", properties.get("name").get("type").asText())
            assertEquals("integer", properties.get("count").get("type").asText())
            assertEquals("number", properties.get("price").get("type").asText())
            assertEquals("boolean", properties.get("active").get("type").asText())
        }

        @Test
        fun `toJsonSchema includes required array for ONE cardinality`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("requiredField", "string", Cardinality.ONE),
                    ValuePropertyDefinition("optionalField", "string", Cardinality.OPTIONAL),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val required = parsed.get("required")

            assertNotNull(required)
            assertTrue(required.isArray)

            val requiredFields = required.map { it.asText() }
            assertTrue(requiredFields.contains("requiredField"))
            assertFalse(requiredFields.contains("optionalField"))
        }

        @Test
        fun `toJsonSchema handles array types with items`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("tags", "string", Cardinality.LIST),
                )
            )

            val schema = Tool.InputSchema.of(domainType)
            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val tagsProperty = parsed.get("properties").get("tags")

            assertEquals("array", tagsProperty.get("type").asText())

            val items = tagsProperty.get("items")
            assertNotNull(items, "Array schema must have 'items' property")
            assertEquals("string", items.get("type").asText())
        }

        @Test
        fun `toJsonSchema handles nested objects`() {
            val addressType = DynamicType(
                name = "Address",
                description = "Address type",
                ownProperties = listOf(
                    ValuePropertyDefinition("street", "string"),
                    ValuePropertyDefinition("city", "string"),
                ),
            )

            val personType = DynamicType(
                name = "Person",
                description = "Person type",
                ownProperties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    DomainTypePropertyDefinition("address", addressType),
                ),
            )

            val schema = Tool.InputSchema.of(personType)
            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val properties = parsed.get("properties")

            val addressProperty = properties.get("address")
            assertEquals("object", addressProperty.get("type").asText())

            val nestedProperties = addressProperty.get("properties")
            assertNotNull(nestedProperties)
            assertTrue(nestedProperties.has("street"))
            assertTrue(nestedProperties.has("city"))
        }
    }

    @Nested
    inner class InheritanceTest {

        @Test
        fun `includes inherited properties from parent`() {
            val parentType = DynamicType(
                name = "Entity",
                description = "Base entity",
                ownProperties = listOf(
                    ValuePropertyDefinition("id", "string"),
                    ValuePropertyDefinition("createdAt", "string"),
                ),
            )

            val childType = DynamicType(
                name = "User",
                description = "User entity",
                ownProperties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    ValuePropertyDefinition("email", "string"),
                ),
                parents = listOf(parentType),
            )

            val schema = Tool.InputSchema.of(childType)

            assertEquals(4, schema.parameters.size)
            assertNotNull(schema.parameters.find { it.name == "id" })
            assertNotNull(schema.parameters.find { it.name == "createdAt" })
            assertNotNull(schema.parameters.find { it.name == "name" })
            assertNotNull(schema.parameters.find { it.name == "email" })
        }
    }

    @Nested
    inner class ToolCreationTest {

        @Test
        fun `Tool create with DomainType schema`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("query", "string", description = "Search query"),
                    ValuePropertyDefinition("limit", "int", description = "Max results"),
                )
            )

            val tool = Tool.create(
                "search",
                "Search for items",
                Tool.InputSchema.of(domainType),
            ) { input ->
                Tool.Result.text("Found results for: $input")
            }

            assertEquals("search", tool.definition.name)
            assertEquals("Search for items", tool.definition.description)
            assertEquals(2, tool.definition.inputSchema.parameters.size)

            val queryParam = tool.definition.inputSchema.parameters.find { it.name == "query" }
            assertNotNull(queryParam)
            assertEquals(Tool.ParameterType.STRING, queryParam!!.type)
            assertEquals("Search query", queryParam.description)
        }

        @Test
        fun `Tool of with DomainType schema and metadata`() {
            val domainType = createSimpleDomainType(
                properties = listOf(
                    ValuePropertyDefinition("content", "string"),
                )
            )

            val tool = Tool.of(
                name = "generateReport",
                description = "Generate a report",
                inputSchema = Tool.InputSchema.of(domainType),
                metadata = Tool.Metadata(returnDirect = true),
            ) { input ->
                Tool.Result.text("Report: $input")
            }

            assertEquals("generateReport", tool.definition.name)
            assertTrue(tool.metadata.returnDirect)
        }

        @Test
        fun `Tool with nested DomainType schema can be called`() {
            val addressType = DynamicType(
                name = "Address",
                description = "Address",
                ownProperties = listOf(
                    ValuePropertyDefinition("city", "string"),
                ),
            )

            val personType = DynamicType(
                name = "Person",
                description = "Person",
                ownProperties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    DomainTypePropertyDefinition("address", addressType),
                ),
            )

            val tool = Tool.create(
                "createPerson",
                "Create a person",
                Tool.InputSchema.of(personType),
            ) { input ->
                Tool.Result.text("Created person from: $input")
            }

            val result = tool.call("""{"name": "John", "address": {"city": "NYC"}}""")

            assertTrue(result is Tool.Result.Text)
            assertTrue((result as Tool.Result.Text).content.contains("John"))
        }

        @Test
        fun `Tool definition toJsonSchema includes nested properties`() {
            val itemType = DynamicType(
                name = "Item",
                description = "Item",
                ownProperties = listOf(
                    ValuePropertyDefinition("name", "string"),
                    ValuePropertyDefinition("price", "double"),
                ),
            )

            val orderType = DynamicType(
                name = "Order",
                description = "Order",
                ownProperties = listOf(
                    ValuePropertyDefinition("orderId", "string"),
                    DomainTypePropertyDefinition("items", itemType, Cardinality.LIST),
                ),
            )

            val tool = Tool.create(
                "placeOrder",
                "Place an order",
                Tool.InputSchema.of(orderType),
            ) { Tool.Result.text("Order placed") }

            val jsonSchema = tool.definition.inputSchema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)

            val itemsProperty = parsed.get("properties").get("items")
            assertEquals("array", itemsProperty.get("type").asText())

            val itemsSchema = itemsProperty.get("items")
            assertNotNull(itemsSchema)
            assertTrue(itemsSchema.has("properties"))
            assertTrue(itemsSchema.get("properties").has("name"))
            assertTrue(itemsSchema.get("properties").has("price"))
        }
    }
}
