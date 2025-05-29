package com.embabel.agent.a2a.server

import com.embabel.agent.a2a.spec.AgentCard
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller("well-known")
class A2AController {

    @GetMapping("/agent.json")
    fun agentCard(): AgentCard {
        TODO()
    }
}