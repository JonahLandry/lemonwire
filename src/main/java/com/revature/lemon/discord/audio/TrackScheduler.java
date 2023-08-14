package com.revature.lemon.discord.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TrackScheduler extends AudioEventAdapter {
    public final AudioPlayer player;
    public final BlockingQueue<AudioTrack> queue;
    public Integer doLoop;

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
        this.doLoop = 0;
    }

    public void queue(AudioTrack track) {
        if (!this.player.startTrack(track, true)) {
            this.queue.offer(track);
        }
    }

    public boolean clear() {
        try {
            this.queue.clear();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean nextTrack() {
        try {
            this.player.startTrack(this.queue.poll(), false);
            return true;
        }
        catch (Exception E){
            return false;
        }
    }


    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (endReason.mayStartNext && this.doLoop == 0) {
            nextTrack();
        }
        if (endReason.mayStartNext && this.doLoop == 1) {
            System.out.println("ERROR V: " + track.getInfo().title + " Original title");
            this.queue.offer(track.makeClone());
            nextTrack();
        }
        if (endReason.mayStartNext && this.doLoop == 2) {
            this.player.startTrack(track.makeClone(), false);
        }
    }
}
