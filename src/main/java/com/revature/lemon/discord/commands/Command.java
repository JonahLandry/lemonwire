package com.revature.lemon.discord.commands;

import discord4j.core.event.domain.message.MessageCreateEvent;
import reactor.core.publisher.Mono;

public interface Command {
    // Since we're being reactive we need a reactive return type.
    Mono<Void> execute(MessageCreateEvent event);
}
