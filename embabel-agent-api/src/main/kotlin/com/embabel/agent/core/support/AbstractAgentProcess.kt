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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.StuckHandlingResultCode
import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.core.*
import com.embabel.agent.event.*
import com.embabel.agent.spi.DelayedActionExecutionSchedule
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.spi.ProntoActionExecutionSchedule
import com.embabel.agent.spi.ScheduledActionExecutionSchedule
import com.embabel.agent.spi.support.AgenticEventListenerToolsStats
import com.embabel.plan.Planner
import com.embabel.plan.WorldState
import com.embabel.plan.goap.WorldStateDeterminer
import com.fasterxml.jackson.annotation.JsonIgnore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstract implementation of AgentProcess that provides common functionality
 */
abstract class AbstractAgentProcess(
    override val id: String,
    override val parentId: String?,
    override val agent: Agent,
    protected val processOptions: ProcessOptions,
    protected val blackboard: Blackboard,
    @get:JsonIgnore
    protected val platformServices: PlatformServices,
    override val timestamp: Instant = Instant.now(),
) : AgentProcess, Blackboard by blackboard {

    protected val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private var _lastWorldState: WorldState? = null

    protected var _goal: com.embabel.plan.Goal? = null

    private val _history: MutableList<ActionInvocation> = mutableListOf()

    private val _status = AtomicReference(AgentProcessStatusCode.NOT_STARTED)

    private var _failureInfo: Any? = null

    override val failureInfo: Any?
        get() = _failureInfo

    override val lastWorldState: WorldState?
        get() = _lastWorldState

    private val agenticEventListenerToolsStats = AgenticEventListenerToolsStats()

    override val goal: com.embabel.plan.Goal? get() = _goal

    override val processContext = ProcessContext(
        platformServices = platformServices.copy(
            eventListener = AgenticEventListener.of(platformServices.eventListener, agenticEventListenerToolsStats),
        ),
        agentProcess = this,
        processOptions = processOptions,
        outputChannel = platformServices.outputChannel,
    )

    /**
     * Get the WorldStateDeterminer for this process
     */
    protected abstract val worldStateDeterminer: WorldStateDeterminer

    /**
     * Get the planner for this process
     */
    protected abstract val planner: Planner<*, *, *>

    override val status: AgentProcessStatusCode
        get() = _status.get()

    override val history: List<ActionInvocation>
        get() = _history.toList()

    override val toolsStats: ToolsStats
        get() = agenticEventListenerToolsStats

    protected fun setStatus(status: AgentProcessStatusCode) {
        _status.set(status)
    }

    override fun kill(): ProcessKilledEvent? {
        setStatus(AgentProcessStatusCode.KILLED)
        return ProcessKilledEvent(this)
    }

    override fun bind(
        key: String,
        value: Any,
    ): Bindable {
        blackboard[key] = value
        processContext.onProcessEvent(
            ObjectBoundEvent(
                agentProcess = this,
                name = key,
                value = value,
            )
        )
        return this
    }

    override fun plusAssign(pair: Pair<String, Any>) {
        bind(pair.first, pair.second)
    }

    // Override set to bind so that delegation works
    override operator fun set(
        key: String,
        value: Any,
    ) {
        bind(key, value)
    }

    override fun addObject(value: Any): Bindable {
        blackboard.addObject(value)
        processContext.onProcessEvent(
            ObjectAddedEvent(
                agentProcess = this,
                value = value,
            )
        )
        return this
    }

    override operator fun plusAssign(value: Any) {
        addObject(value)
    }

    private fun makeRunning(): Boolean {
        val currentStatus = _status.get()
        return when (currentStatus) {
            AgentProcessStatusCode.COMPLETED,
            AgentProcessStatusCode.KILLED, AgentProcessStatusCode.TERMINATED,
                -> {
                logger.warn("Process {} Cannot be made RUNNING as its status is {}", this.id, status)
                return false
            }

            else -> {
                _status.compareAndSet(currentStatus, AgentProcessStatusCode.RUNNING)
                true
            }
        }
    }

    override fun run(): AgentProcess {
        if (!makeRunning()) {
            return this
        }

        if (agent.goals.isEmpty()) {
            logger.info("🤔 Process {} has no goals: {}", this.id, agent.goals)
            error("Agent ${agent.name} has no goals: ${agent.infoString(verbose = true)}")
        }

        tick()
        while (status == AgentProcessStatusCode.RUNNING) {
            val earlyTermination = processOptions.control.earlyTerminationPolicy.shouldTerminate(this)
            if (earlyTermination != null) {
                logger.debug(
                    "Process {} terminated by {} because {}",
                    this.id,
                    earlyTermination.policy,
                    earlyTermination.reason,
                )
                platformServices.eventListener.onProcessEvent(earlyTermination)
                _failureInfo = earlyTermination
                setStatus(AgentProcessStatusCode.TERMINATED)
                return this
            }
            tick()
        }
        when (status) {
            AgentProcessStatusCode.NOT_STARTED -> {
                logger.debug("Process {} is not started: {}", this.id, status)
            }

            AgentProcessStatusCode.RUNNING -> {
                logger.debug("Process {} is happily running: {}", this.id, status)
            }

            AgentProcessStatusCode.COMPLETED -> {
                platformServices.eventListener.onProcessEvent(AgentProcessFinishedEvent(this))
            }

            AgentProcessStatusCode.FAILED -> {
                platformServices.eventListener.onProcessEvent(AgentProcessFinishedEvent(this))
            }

            AgentProcessStatusCode.TERMINATED, AgentProcessStatusCode.KILLED -> {
                // Event will have been raised at the point of termination
            }

            AgentProcessStatusCode.WAITING -> {
                platformServices.eventListener.onProcessEvent(AgentProcessWaitingEvent(this))
            }

            AgentProcessStatusCode.PAUSED -> {
                platformServices.eventListener.onProcessEvent(AgentProcessPausedEvent(this))
                handleStuck(agent)
            }

            AgentProcessStatusCode.STUCK -> {
                platformServices.eventListener.onProcessEvent(AgentProcessStuckEvent(this))
                handleStuck(agent)
            }
        }
        return this
    }

    /**
     * Try to resolve a stuck process using StuckHandler if provided
     */
    protected fun handleStuck(agent: Agent) {
        val stuckHandler = agent.stuckHandler
        if (stuckHandler == null) {
            logger.warn(
                "Process {} is stuck: no handler. History ({}):\n\t{}",
                this.id,
                history.size,
                history.joinToString("\n\t") { it.actionName },
            )
            return
        }
        val result = stuckHandler.handleStuck(this)
        platformServices.eventListener.onProcessEvent(result)
        when (result.code) {
            StuckHandlingResultCode.REPLAN -> {
                logger.info("Process {} unstuck and will replan: {}", this.id, result.message)
                setStatus(AgentProcessStatusCode.RUNNING)
                run()
            }

            StuckHandlingResultCode.NO_RESOLUTION -> {
                logger.warn("Process {} stuck: {}", this.id, result.message)
                setStatus(AgentProcessStatusCode.STUCK)
            }
        }
    }

    override fun tick(): AgentProcess {
        if (!makeRunning()) {
            return this
        }

        val worldState = worldStateDeterminer.determineWorldState()
        _lastWorldState = worldState
        platformServices.eventListener.onProcessEvent(
            AgentProcessReadyToPlanEvent(
                agentProcess = this,
                worldState = worldState,
            )
        )
        logger.debug(
            "Process {} tick (about to plan): {}, blackboard={}",
            id,
            worldState,
            blackboard.infoString(verbose = false),
        )

        // Let subclasses handle the planning and execution
        return formulateAndExecutePlan(worldState)
    }


    /**
     * Execute the plan based on the current world state
     * @param worldState The current world state
     */
    protected abstract fun formulateAndExecutePlan(
        worldState: WorldState,
    ): AgentProcess

    /**
     * Execute an action
     */
    protected fun executeAction(action: Action): ActionStatus {
        val outputTypes: Map<String, DomainType> =
            action.outputs.associateBy({ it.name }, { agent.resolveType(it.type) })
        logger.debug(
            "⚙️ Process {} executing action {}: outputTypes={}",
            id,
            action.name,
            outputTypes,
        )

        val actionExecutionStartEvent = ActionExecutionStartEvent(
            agentProcess = this,
            action = action,
        )
        platformServices.eventListener.onProcessEvent(actionExecutionStartEvent)
        val actionExecutionSchedule = platformServices.operationScheduler.scheduleAction(actionExecutionStartEvent)
        when (actionExecutionSchedule) {
            is ProntoActionExecutionSchedule -> {
                // Do nothing
            }

            is DelayedActionExecutionSchedule -> {
                // Delay and move on
                logger.debug("Process {} delayed action {}: {}", id, action.name, actionExecutionSchedule)
                try {
                    Thread.sleep(actionExecutionSchedule.delay.toMillis())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    _status.set(AgentProcessStatusCode.TERMINATED)
                    return ActionStatus(
                        runningTime = Duration.between(actionExecutionStartEvent.timestamp, Instant.now()),
                        status = ActionStatusCode.FAILED,
                    )
                }
                logger.debug("Process {} delayed action {}: done", id, action.name)
            }

            is ScheduledActionExecutionSchedule -> {
                return ActionStatus(
                    Duration.between(actionExecutionStartEvent.timestamp, Instant.now()),
                    ActionStatusCode.PAUSED
                )
            }
        }

        val timestamp = Instant.now()
        val actionStatus = action.qos.retryTemplate("Action-${action.name}").execute<ActionStatus, Throwable> {
            action.execute(
                processContext = processContext,
                action = action,
            )
        }
        val runningTime = Duration.between(timestamp, Instant.now())
        _history += ActionInvocation(
            actionName = action.name,
            timestamp = timestamp,
            runningTime = runningTime,
        )
        platformServices.eventListener.onProcessEvent(
            actionExecutionStartEvent.resultEvent(
                actionStatus = actionStatus,
            )
        )

        logger.debug("New world state: {}", worldStateDeterminer.determineWorldState())
        return actionStatus
    }

    /**
     * Convert action status to agent process status
     */
    protected fun actionStatusToAgentProcessStatus(actionStatus: ActionStatus): AgentProcessStatusCode {
        return when (actionStatus.status) {
            ActionStatusCode.SUCCEEDED -> {
                logger.debug("Process {} action {} is running", id, actionStatus.status)
                AgentProcessStatusCode.RUNNING
            }

            ActionStatusCode.FAILED -> {
                logger.debug("❌ Process {} action {} failed", id, actionStatus.status)
                AgentProcessStatusCode.FAILED
            }

            ActionStatusCode.WAITING -> {
                logger.debug("⏳ Process {} action {} waiting", id, actionStatus.status)
                AgentProcessStatusCode.WAITING
            }

            ActionStatusCode.PAUSED -> {
                logger.debug("⏳ Process {} action {} paused", id, actionStatus.status)
                AgentProcessStatusCode.PAUSED
            }
        }
    }
}
