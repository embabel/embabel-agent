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
package com.embabel.agent.api.dsl


import com.embabel.agent.api.common.*
import com.embabel.agent.api.common.scope.AgentScopeBuilder
import com.embabel.agent.api.common.support.*
import com.embabel.agent.core.*
import com.embabel.agent.core.support.Rerun
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.util.loggerFor
import java.util.function.Function as JavaFunction

inline fun <reified A, reified B : Any> doSplit(
    noinline splitter: (InputActionContext<A>) -> List<B>,
): TypedAgentScopeBuilder<Unit> = doSplit(
    splitter = splitter,
    aClass = A::class.java,
    bClass = B::class.java,
)

fun <A, B : Any> doSplit(
    splitter: (InputActionContext<A>) -> List<B>,
    aClass: Class<A>,
    bClass: Class<B>,
): TypedAgentScopeBuilder<Unit> {
    val a = ConsumerAction(
        name = "${aClass.name}-<=${bClass.name}",
        description = "${aClass.name}-<=${bClass.name}",
        pre = emptyList(),
        post = emptyList(),
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = true,
        inputClass = aClass,
        toolGroups = emptySet(),
    ) {
        val list = splitter(it)
        it.addAll(list)
    }
    return TypedAgentScopeBuilder(
        name = a.name,
        actions = listOf(a),
    )
}

inline fun <reified A, reified B : Any> split(
    noinline splitter: (a: A) -> List<B>,
): TypedAgentScopeBuilder<Unit> = doSplit({ splitter(it.input) }, A::class.java, B::class.java)

inline infix fun <reified A, reified B, reified C> ((A) -> B).andThenDo(
    crossinline that: (InputActionContext<B>) -> C,
): TypedAgentScopeBuilder<C> {
    val javaFunction1 = JavaFunction<InputActionContext<A>, B> { ctx -> this(ctx.input) }
    val javaFunction2 = JavaFunction<InputActionContext<B>, C> { ctx -> that(ctx) }

    return chain(
        { javaFunction1.apply(it) },
        { javaFunction2.apply(it) },
        A::class.java,
        B::class.java,
        C::class.java
    )
}

inline infix fun <reified A, reified B, reified C> ((A) -> B).andThen(
    crossinline that: (B) -> C,
): TypedAgentScopeBuilder<C> {
    val javaFunction1 = JavaFunction<InputActionContext<A>, B> { ctx -> this(ctx.input) }
    val javaFunction2 = JavaFunction<InputActionContext<B>, C> { ctx -> that(ctx.input) }

    return chain(
        { javaFunction1.apply(it) },
        { javaFunction2.apply(it) },
        A::class.java,
        B::class.java,
        C::class.java
    )
}


inline fun <reified A, reified B, reified C> JavaFunction<InputActionContext<A>, B>.javaAndThen(
    that: JavaFunction<InputActionContext<B>, C>,
): TypedAgentScopeBuilder<C> {
    return chain({ this.apply(it) }, { that.apply(it) }, A::class.java, B::class.java, C::class.java)
}

inline fun <reified A, reified B, reified C> JavaFunction<A, B>.andThen(
    that: JavaFunction<InputActionContext<B>, C>,
): TypedAgentScopeBuilder<C> {
    return chain({ this.apply(it.input) }, { that.apply(it) }, A::class.java, B::class.java, C::class.java)
}

fun <A, B, C> JavaFunction<InputActionContext<A>, B>.andThen(
    that: JavaFunction<InputActionContext<B>, C>,
    aClass: Class<A>,
    bClass: Class<B>,
    cClass: Class<C>,
): TypedAgentScopeBuilder<C> {
    return chain({ this.apply(it) }, { that.apply(it) }, aClass, bClass, cClass)
}

/**
 * Creates a chain from A to B via C. Emits actions.
 */
fun <A, B, C> chain(
    a: (context: InputActionContext<A>) -> B,
    b: (context: InputActionContext<B>) -> C,
    aClass: Class<A>,
    bClass: Class<B>,
    cClass: Class<C>,
): TypedAgentScopeBuilder<C> {
    val actions: List<Action> = listOf(
        TransformationAction(
            name = "chain-0",
            description = "chain element 0",
            cost = { 0.0 },
            value = { 0.0 },
            canRerun = false,
            inputClass = aClass,
            outputClass = bClass,
            toolGroups = emptySet(),
        ) {
            a.invoke(it)
        },
        TransformationAction(
            name = "chain-1",
            description = "chain element 1",
            cost = { 0.0 },
            value = { 0.0 },
            canRerun = false,
            inputClass = bClass,
            outputClass = cClass,
            toolGroups = emptySet(),
        ) {
            b.invoke(it)
        })
    return TypedAgentScopeBuilder(
        name = MobyNameGenerator.generateName(),
        actions = actions,
    )
}


/**
 * Branch from A to B or C using Kotlin reified types.
 * Makes conditionals easy to express.
 */
fun <A, B, C> branch(
    a: (context: InputActionContext<A>) -> Branch<B, C>,
    aClass: Class<A>,
    bClass: Class<B>,
    cClass: Class<C>,
): TypedAgentScopeBuilder<Branch<B, C>> {
    val branchAction =
        BranchingAction<A, B, C>(
            name = "chain-0",
            description = "chain element 0",
            cost = { 0.0 },
            value = { 0.0 },
            canRerun = false,
            inputClass = aClass,
            leftOutputClass = bClass,
            rightOutputClass = cClass,
            toolGroups = emptySet(),
        ) {
            a.invoke(it)
        }
    return TypedAgentScopeBuilder(
        name = MobyNameGenerator.generateName(),
        actions = listOf(branchAction),
    )
}

/**
 * Convenience method to branch from A to B or C using Kotlin reified types.
 */
inline fun <reified A, reified B, reified C> branch(
    noinline a: (context: InputActionContext<A>) -> Branch<B, C>,
): TypedAgentScopeBuilder<Branch<B, C>> {
    return branch(a, A::class.java, B::class.java, C::class.java)
}

/**
 * Convenience method to chain A to B via C using Kotlin reified types.
 */
inline fun <reified A, reified B, reified C> chain(
    noinline a: (context: InputActionContext<A>) -> B,
    noinline b: (context: InputActionContext<B>) -> C,
): TypedAgentScopeBuilder<C> {
    return chain(a, b, A::class.java, B::class.java, C::class.java)
}


fun <A, B, C> aggregate(
    transforms: List<(context: InputActionContext<A>) -> B>,
    merge: (list: List<B>, context: OperationContext) -> C,
    aClass: Class<A>,
    bClass: Class<B>,
    cClass: Class<C>,
): TypedAgentScopeBuilder<C> {
    val allCompletedCondition = ComputedBooleanCondition(
        name = "All<${bClass.name}=>${cClass.name}",
        evaluator = { it, condition ->
            it.objectsOfType(bClass).size == transforms.size
        }
    )
    val actions = mutableListOf<Action>()

    val transformActions = transforms.mapIndexed { index, transform ->
        TransformationAction(
            name = "${aClass.name}=>${bClass.name}-$index",
            description = "Transform $aClass to $bClass",
            post = listOf(allCompletedCondition.name),
            cost = { 0.0 },
            value = { 0.0 },
            canRerun = true,
            inputClass = aClass,
            outputClass = bClass,
            toolGroups = emptySet(),
        ) {
            transform.invoke(it)
        }
    }
    actions += transformActions
    val mergeAction = SupplierAction(
        name = "List<${bClass.name}>=>${cClass.name}",
        description = "Aggregate list $bClass to $cClass",
        pre = transformActions.map { Rerun.hasRunCondition(it) } + allCompletedCondition.name,
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = true,
        outputClass = cClass,
        toolGroups = emptySet(),
    ) { context ->
        merge(context.objects.filterIsInstance(bClass), context)
    }
    actions += mergeAction
    return TypedAgentScopeBuilder(
        name = MobyNameGenerator.generateName(),
        actions = actions,
        goals = emptySet(),
        conditions = setOf(allCompletedCondition),
    )
}

/**
 * Aggregate taking 2 inputs
 */
@Suppress("UNCHECKED_CAST")
fun <A1, A2, B : Any, C> biAggregate(
    transforms: List<(context: BiInputActionContext<A1, A2>) -> B>,
    merge: (list: List<B>) -> C,
    a1Class: Class<A1>,
    a2Class: Class<A2>,
    bClass: Class<B>,
    cClass: Class<C>,
): TypedAgentScopeBuilder<C> {
    val allCompletedCondition = ComputedBooleanCondition(
        name = "List<${bClass.name}>=>${cClass.name}",
        evaluator = { it, condition ->
            it.objectsOfType(bClass).size == transforms.size
        }
    )
    val actions = mutableListOf<Action>()

    val transformActions = transforms.mapIndexed { index, transform ->
        MultiTransformationAction<B>(
            name = "${a1Class.name}+${a2Class.name}=>${bClass.name}-$index",
            description = "Transform ${a1Class.name}+${a2Class.name} to $bClass",
            pre = emptyList(),
            post = listOf(allCompletedCondition.name),
            cost = { 0.0 },
            value = { 0.0 },
            canRerun = true,
            inputs = setOf(
                IoBinding(IoBinding.DEFAULT_BINDING, a1Class.name),
                IoBinding(IoBinding.DEFAULT_BINDING, a2Class.name),
            ),
            inputClasses = listOf(a1Class, a2Class),
            outputClass = bClass,
            toolGroups = emptySet(),
        ) {
            transform.invoke(BiInputActionContext(it.input[0] as A1, it.input[1] as A2, it))
        }
    }
    actions += transformActions
    val mergeAction = TransformationAction(
        name = "List<${bClass.name}>=>${cClass.name}",
        description = "Aggregate list $bClass to $cClass",
        pre = transformActions.map { Rerun.hasRunCondition(it) } + allCompletedCondition.name,
        post = emptyList(),
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = true,
        inputClass = bClass,
        outputClass = cClass,
        toolGroups = emptySet(),
    ) {
        val cList = it.objects.filterIsInstance<B>(bClass)
        merge(cList)
    }
    actions += mergeAction
    return TypedAgentScopeBuilder(
        name = MobyNameGenerator.generateName(),
        actions = actions,
        goals = emptySet(),
        conditions = setOf(allCompletedCondition),
    )
}

/**
 * Run all the transforms and merge the results.
 */
inline fun <reified A, reified B, reified C> aggregate(
    transforms: List<(context: InputActionContext<A>) -> B>,
    noinline merge: (list: List<B>, context: OperationContext) -> C,
): TypedAgentScopeBuilder<C> {
    return aggregate(
        transforms, merge, A::class.java, B::class.java, C::class.java
    )
}

data class BiInputActionContext<A1, A2>(
    val input1: A1,
    val input2: A2,
    val actionContext: ActionContext,
) : InputsActionContext, ActionContext by actionContext {

    override val inputs: List<Any> get() = listOfNotNull(input1, input2)
}

inline fun <reified A1, reified A2, reified B : Any, reified C> biAggregate(
    transforms: List<(context: BiInputActionContext<A1, A2>) -> B>,
    noinline merge: (list: List<B>) -> C,
): TypedAgentScopeBuilder<C> {
    return biAggregate<A1, A2, B, C>(
        transforms,
        merge,
        a1Class = A1::class.java,
        a2Class = A2::class.java,
        bClass = B::class.java,
        cClass = C::class.java,
    )
}

/**
 * Special aggregate that works like an accumulator. If the condition is not
 * satisfied, your transformer methods will be called again with the latest updated context.
 */
inline fun <reified A, reified B : Any, reified C> repeatableAggregate(
    startWith: C,
    transforms: List<(context: BiInputActionContext<A, C>) -> B>,
    noinline merge: (list: List<B>) -> C,
): TypedAgentScopeBuilder<C> {
    val asb = biAggregate<A, C, B, C>(
        transforms,
        merge,
        a1Class = A::class.java,
        a2Class = C::class.java,
        bClass = B::class.java,
        cClass = C::class.java,
    )
    val populator: Action = SupplierAction(
        name = "repeatable-aggregate",
        description = "Repeatable aggregate",
        pre = emptyList(),
        post = emptyList(),
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = true,
        outputClass = C::class.java,
        toolGroups = emptySet(),
    ) {
        startWith
    }
    return asb.copy(
        actions = asb.actions + populator,
    )
}


fun <C> repeat(
    what: () -> TypedAgentScopeBuilder<C>,
    // TODO gather this
    until: (c: C, context: OperationContext) -> Boolean,
    cClass: Class<C>,
): TypedAgentScopeBuilder<C> {
    val conditionName = "repeat-until-${cClass.name}"
    val untilCondition = ComputedBooleanCondition(
        name = conditionName,
        evaluator = { it, condition ->
            val input = it.last(cClass)
            if (input == null) {
                return@ComputedBooleanCondition false
            }
            until(input, it)
        })
    val doerScope = what.invoke().createAgentScope()
    val completionAction = TransformationAction(
        name = "repeat",
        description = "Repeat until condition is met",
        pre = listOf(untilCondition.name),
        post = emptyList(),
        cost = { 0.0 },
        value = { 0.0 },
        canRerun = true,
        inputClass = cClass,
        outputClass = cClass,
        toolGroups = emptySet(),
    ) {
        TODO()
    }
    return TypedAgentScopeBuilder(
        name = MobyNameGenerator.generateName(),
        actions = doerScope.actions + completionAction,
        conditions = setOf(untilCondition),
    )
}

inline fun <reified C> repeat(
    noinline what: () -> TypedAgentScopeBuilder<C>,
    noinline until: (c: C, context: OperationContext) -> Boolean,
): TypedAgentScopeBuilder<C> {
    return repeat(what, until, C::class.java)
}

/**
 * AgentScopeBuilder that emits actions and can be built on further.
 * @param O the output type of the actions
 */
data class TypedAgentScopeBuilder<O>(
    val name: String,
    val provider: String = "embabel",
    val actions: List<Action> = emptyList(),
    val goals: Set<Goal> = emptySet(),
    val conditions: Set<Condition> = emptySet(),
    val opaque: Boolean = false,
) : AgentScopeBuilder {

    override fun createAgentScope(): AgentScope {
        return AgentScope(
            name = name,
            actions = actions,
            goals = goals,
            conditions = conditions,
            opaque = opaque,
        )
    }

    fun <O : Any> asSubProcess(
        context: ActionContext,
        outputClass: Class<O>,
    ): O {

        val withExtraGoal = AgentScope(
            name = name,
            actions = actions,
            goals = goals + Goal(
                name = name,
                description = "description",
                inputs = setOf(
                    IoBinding(
                        type = outputClass,
                        name = IoBinding.DEFAULT_BINDING,
                    ),
                ),
            ),
            conditions = conditions,
        )
        val agent = withExtraGoal.createAgent(
            name = this.name,
            provider = this.provider,
            description = this.name,
        )
        val singleAction = agentTransformer(
            agent = agent,
            inputClass = Unit::class.java,
            outputClass = outputClass,
        )

        singleAction.execute(
            processContext = context.processContext,
        )
        return context.last(outputClass) ?: throw IllegalStateException(
            "No output of type ${outputClass.name} found in context"
        )
    }

    inline fun <reified O : Any> asSubProcess(
        context: ActionContext,
    ): O {
        return asSubProcess(
            context = context,
            outputClass = O::class.java,
        )
    }

    inline infix fun <reified F> andThenDo(
        fn: Transformation<O, F>,
    ): TypedAgentScopeBuilder<F> = andThenDo(fn, F::class.java)

    fun <F> andThenDo(
        fn: Transformation<O, F>,
        fClass: Class<F>,
    ): TypedAgentScopeBuilder<F> {
        // TODO is this safe?
        val lastAction = actions.last()
        val extraAction = TransformationAction(
            name = "extra=>${fClass.name}",
            description = "Extra step to ${fClass.name}",
            pre = listOf(Rerun.hasRunCondition(lastAction)),
            post = emptyList(),
            cost = { 0.0 },
            value = { 0.0 },
            canRerun = true,
            // TODO this is nasty
            inputClass = (lastAction as TransformationAction<*, *>).outputClass,
            outputClass = fClass,
            toolGroups = emptySet(),
        ) {
            loggerFor<TypedAgentScopeBuilder<*>>().info("Running extra action {}", name)
            fn.transform(it as TransformationActionContext<O, F>)
        }
        return this.copy(actions = this.actions + extraAction) as TypedAgentScopeBuilder<F>
    }

    /**
     * Changes output
     */
    fun <F> andThen(
        fn: (e: O) -> F,
        fClass: Class<F>,
    ): TypedAgentScopeBuilder<F> =
        andThenDo({ fn(it.input) }, fClass)


    inline infix fun <reified F> andThen(noinline fn: (e: O) -> F): TypedAgentScopeBuilder<F> {
        return andThen(fn, F::class.java)
    }
}

inline fun <reified I, reified O : Any> runAgent(
    agent: Agent,
    context: TransformationActionContext<I, O>,
): O {
    val singleAction = agent.asAction<Any, O>()
    singleAction.execute(
        processContext = context.processContext,
    )
    return context.last<O>() ?: throw IllegalStateException(
        "No output of type ${O::class.java} found in context"
    )
}
