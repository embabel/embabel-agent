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
package com.embabel.boogie.neo.common

import com.embabel.common.util.loggerFor
import org.slf4j.Logger
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.charset.Charset

/**
 * Resolves logical query names to Cypher queries.
 */
interface LogicalQueryResolver {

    val description: String

    /**
     * Resolve a logical query name to a Cypher query.
     * Return null without error if it cannot be resolved.
     * Clients may wish to try another query to recover.
     */
    fun resolve(name: String): String?
}

const val DEFAULT_CYPHER_LOCATION = "classpath:cypher"

@Service
data class FixedLocationLogicalQueryResolver(
    val location: String = DEFAULT_CYPHER_LOCATION,
    private val resourceLoader: ResourceLoader = DefaultResourceLoader(),
) : LogicalQueryResolver {
    override val description: String = "Load from fixed location $location, expecting .cypher suffix"

    private val logger: Logger = loggerFor<FixedLocationLogicalQueryResolver>()

    override fun resolve(name: String): String? =
        try {
            logger.debug("Resource Loader in use = {}", resourceLoader::class.java.name)
            resourceLoader.getResource("$location/$name.cypher")
                .getContentAsString(Charset.defaultCharset())
        } catch (e: IOException) {
            logger.warn("Could not load logical query '$name' from '$location'", e)
            null
        }
}
