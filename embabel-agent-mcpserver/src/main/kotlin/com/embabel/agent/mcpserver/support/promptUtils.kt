package com.embabel.agent.mcpserver.support

import com.embabel.common.util.NameUtils
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method

/**
 * Extracts MCP prompt arguments from a given type,
 * excluding fields that match methods in the excluded interfaces.
 */
internal fun argumentsFromType(excludedInterfaces: Set<Class<*>>, type: Class<*>): List<McpSchema.PromptArgument> {
    val excludedFields: Iterable<Method> = excludedInterfaces.flatMap {
        it.methods.toList()
    }
    val args = mutableListOf<McpSchema.PromptArgument>()
    ReflectionUtils.doWithFields(type) { field ->
        if (field.isSynthetic) {
            return@doWithFields
        }
        if (excludedFields.any { NameUtils.beanMethodToPropertyName(it.name) == field.name }) {
            return@doWithFields
        }
        val name = field.name
        val description = field.getAnnotation(JsonPropertyDescription::class.java)?.value ?: name
        val descriptionWithType = "$description: ${field.type.simpleName}"
        args.add(McpSchema.PromptArgument(name, descriptionWithType, true))
    }
    return args
}