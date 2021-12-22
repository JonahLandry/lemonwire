package com.revature.lemon.discord.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import discord4j.common.util.Snowflake;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.revature.lemon.discord.botDriver.PLAYER_MANAGER;

public final class GuildAudioManager {

    private static final Map<Snowflake, GuildAudioManager> MANAGERS = new ConcurrentHashMap<>();

    public static GuildAudioManager of(final Snowflake id) {
        return MANAGERS.computeIfAbsent(id, ignored -> new GuildAudioManager());
    }

    private final AudioPlayer player;
    private final TrackScheduler scheduler;
    private final LavaPlayerAudioProvider provider;
    private Long idleTime;

    private GuildAudioManager() {
        player = PLAYER_MANAGER.createPlayer();
        scheduler = new TrackScheduler(player);
        provider = new LavaPlayerAudioProvider(player);
        idleTime = System.currentTimeMillis();

        player.addListener(scheduler);
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public TrackScheduler getScheduler() {
        return scheduler;
    }

    public LavaPlayerAudioProvider getProvider() {
        return provider;
    }

    public Long getIdleTime() {
        return idleTime;
    }

    public void setIdleTime(Long idleTime) {
        this.idleTime = idleTime;
    }
}