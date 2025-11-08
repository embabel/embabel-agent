package com.embabel.agent.remote.action

import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.IoBinding
import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.core.types.ZeroToOne

// TODO improve action interface hierarchy and align with ActionMetadata
/**
 * Data from a remote action
 * @param url url of the remote action
 */
data class RestActionMetadata(
    val url: String,
    override val name: String,
    override val description: String,
    val inputs: Set<IoBinding>,
    val outputs: Set<IoBinding>,
    val pre: List<String>,
    val post: List<String>,
    val cost: ZeroToOne,
    val value: ZeroToOne,
    val canRerun: Boolean,
    val qos: ActionQos,
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
