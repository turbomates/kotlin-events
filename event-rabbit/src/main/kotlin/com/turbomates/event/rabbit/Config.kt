package com.turbomates.event.rabbit

import com.rabbitmq.client.ConnectionFactory

data class Config(val connectionFactory: ConnectionFactory, val exchange: String, val queuePrefix: String)
