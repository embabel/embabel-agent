package com.embabel.agent.remote.action

import com.embabel.agent.core.IoBinding
import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.core.types.ZeroToOne
import com.fasterxml.jackson.annotation.JsonProperty

data class Io(
    val name: String,
    val type: String,
) {
    fun toIoBinding(): IoBinding = IoBinding(name, type)
}

// TODO improve action interface hierarchy and align with ActionMetadata
/**
 * Data from a remote action
 * @param url url of the remote action. Relative to the server base URL
 */
data class RestActionMetadata(
    val url: String,
    override val name: String,
    override val description: String,
    val inputs: Set<Io>,
    val outputs: Set<Io>,
    val pre: List<String>,
    val post: List<String>,
    val cost: ZeroToOne,
    val value: ZeroToOne,
    @field:JsonProperty("can_rerun")
    val canRerun: Boolean,
//    val qos: ActionQos,
) : NamedAndDescribed

/**
 * Payload to register a remote server
 * @param baseUrl URL of the Embabel server below /api/v1
 */
data class RestServerRegistration(
    val baseUrl: String,
    override val name: String,
    override val description: String,
) : NamedAndDescribed
