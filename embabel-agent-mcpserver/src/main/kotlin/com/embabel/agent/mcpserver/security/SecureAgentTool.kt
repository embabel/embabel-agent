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
package com.embabel.agent.mcpserver.security

// Declares the security contract for an Embabel agent action exposed as a remote MCP tool.
//
// Accepts any Spring Security SpEL expression, identical syntax to @PreAuthorize,
// evaluated against the current Authentication at the point of tool invocation,
// before Embabel's GOAP planner executes the action body.
//
// Supported expressions:
//
//   @SecureAgentTool("hasAuthority('finance:admin')")
//   @SecureAgentTool("hasAnyAuthority('finance:read', 'finance:admin')")
//   @SecureAgentTool("hasRole('ADMIN')")
//   @SecureAgentTool("@myPolicy.canAccess(authentication, #request)")
//   @SecureAgentTool("hasAuthority('finance:read') and #request.amount < 10000")
//
// Placement: combine with @Action and optionally @AchievesGoal on the same method:
//
//   @SecureAgentTool("hasAuthority('payments:write')")
//   @AchievesGoal(description = "Process a payment", export = Export(...))
//   @Action
//   fun processPayment(request: PaymentRequest, context: OperationContext): PaymentResult
//
// SecureAgentToolAspect intercepts the call and evaluates the SpEL expression using
// Spring Security's MethodSecurityExpressionHandler. An AccessDeniedException is thrown
// if the expression evaluates to false, resulting in a 403 at the MCP transport layer.
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class SecureAgentTool(
    val value: String,
)
