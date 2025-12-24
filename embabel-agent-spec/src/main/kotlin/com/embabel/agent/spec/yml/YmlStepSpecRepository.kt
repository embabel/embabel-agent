/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spec.yml

import com.embabel.agent.spec.model.StepSpec
import com.embabel.agent.spec.persistence.StepSpecRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Look for YML files in a directory to load and save StepDefinition entities
 * @param dir Directory to load/save YML files
 */
class YmlStepSpecRepository(
    val dir: String,
) : StepSpecRepository {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val yamlMapper = ObjectMapper(YAMLFactory()).apply {
        registerKotlinModule()
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    override fun save(entity: StepSpec<*>): StepSpec<*> {
        val dirFile = File(dir)
        if (!dirFile.exists()) {
            dirFile.mkdirs()
        }

        val file = File(dirFile, "${entity.name}.yml")
        yamlMapper.writeValue(file, entity)
        logger.info("Saved entity to {}", file.absolutePath)
        return entity
    }

    override fun findAll(): Iterable<StepSpec<*>> {
        val dirFile = File(dir)
        if (!dirFile.exists()) {
            return emptyList()
        }

        return dirFile.listFiles { file -> file.extension == "yml" }
            ?.mapNotNull { file ->
                try {
                    yamlMapper.readValue<StepSpec<*>>(file)
                } catch (e: Exception) {
                    logger.warn("Failed to read {}: {}", file.name, e.message)
                    null
                }
            }
            ?: emptyList()
    }
}
