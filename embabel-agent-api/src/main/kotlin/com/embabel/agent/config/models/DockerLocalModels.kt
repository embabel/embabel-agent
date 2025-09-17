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
package com.embabel.agent.config.models

import com.embabel.common.util.ExcludeFromJacocoGeneratedReport

/**
 * Docker local models
 * This class will always be loaded, but models won't be loaded
 * from the Docker endpoint unless the "docker" profile is set.
 * Model names will be precisely as reported from
 * http://localhost:12434/engines/v1/models (assuming default port).
 */
@ExcludeFromJacocoGeneratedReport(reason = "Docker model configuration can't be unit tested")
class DockerLocalModels(
) {
    companion object {
        const val DOCKER_PROFILE = "docker"

        const val PROVIDER = "Docker"
    }
}
