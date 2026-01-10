package com.embabel.agent.autoconfigure.netty;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyClientFactoryPropertiesTest {

    @Test
    void connectTimeout() {
        var defaults = new NettyClientFactoryProperties(null, null);
        assertEquals(Duration.ofSeconds(25), defaults.connectTimeout());

        var custom = new NettyClientFactoryProperties(Duration.ofSeconds(5), null);
        assertEquals(Duration.ofSeconds(5), custom.connectTimeout());
    }

    @Test
    void readTimeout() {
        var defaults = new NettyClientFactoryProperties(null, null);
        assertEquals(Duration.ofMinutes(1), defaults.readTimeout());

        var custom = new NettyClientFactoryProperties(null, Duration.ofSeconds(10));
        assertEquals(Duration.ofSeconds(10), custom.readTimeout());
    }
}