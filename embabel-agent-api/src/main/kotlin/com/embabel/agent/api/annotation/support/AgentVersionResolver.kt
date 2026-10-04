package com.embabel.agent.api.annotation.support

import com.embabel.common.core.types.Semver
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import java.io.IOException

/**
 * Resolve the version of the agent. The resolution order is
 *  - The version provided while defining the agent @Agent(version = "...")
 *  - The version provided in the Spring Boot build properties.
 *  - Semver.DEFAULT_VERSION as the final fallback.
 */

class AgentVersionResolver {
    // Load and cache the version once upon instantiation
    private val cachedBuildVersion: String? by lazy {
        try {
            val resource = ClassPathResource("META-INF/build-info.properties")
            if (resource.exists()) {
                val props = PropertiesLoaderUtils.loadProperties(resource)
                props.getProperty("build.version")?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    fun resolveVersion(
        annotationVersion: String,
    ): String {
        // 1. If an explicit version is defined on the annotation, use it.
        if (annotationVersion != Semver.DEFAULT_VERSION) {
            return annotationVersion
        }

        // 2. Fall back to the version loaded once during startup/instantiation.
        cachedBuildVersion?.let { return it }

        // 3. Default fallback.
        return Semver.DEFAULT_VERSION
    }
}