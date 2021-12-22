package com.revature.lemon.discord;


import com.revature.lemon.discord.audio.GuildAudioManager;
import com.revature.lemon.discord.audio.TrackScheduler;
import com.revature.lemon.discord.commands.Command;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Member;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class botDriver {
    // ##################### FUNCTIONS ########################
    // Boolean statement to learn if somethingi s aurl or not
    private static boolean isUrl(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // Formatting for any timestamps we need
    private static String formatTime(long timeInMillis) {
        final long hours = timeInMillis / TimeUnit.HOURS.toMillis(1);
        final long minutes = timeInMillis / TimeUnit.MINUTES.toMillis(1);
        final long seconds = timeInMillis % TimeUnit.MINUTES.toMillis(1) / TimeUnit.SECONDS.toMillis(1);

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }



    // #################### VARIABLES ########################
    // Create our global player manager to keep track of our audio players
    public static final AudioPlayerManager PLAYER_MANAGER;
    // Map for our commands
    private static final Map<String , Command> commands = new HashMap<>();

    static {
        PLAYER_MANAGER = new DefaultAudioPlayerManager();
        // This is an optimization strategy that Discord4J can utilize to minimize allocations
        PLAYER_MANAGER.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        // Allow playerManager to parse remote sources like YouTube links
        AudioSourceManagers.registerRemoteSources(PLAYER_MANAGER);
        AudioSourceManagers.registerLocalSource(PLAYER_MANAGER);
    }


    // ################## MAIN #########################
    public static void main(String[] args) {
        // ################# VARIABLES #######################
        // Write down our bot token so we can create a session.
        // Make sure the bot token is set in the arguments for this file!
        String bToken = new String(args[0]);
        String APIHead = "http://Lemonapiwebapp-env.eba-8cqvu5dm.us-west-1.elasticbeanstalk.com/lemon";


        // ################### COMMANDS #########################
        // JOIN
        // Constructs command to get the bot in a voice channel
        commands.put("join", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                    // Find the current user's voice channel and connect to it
                    message.getAuthorAsMember()
                        .flatMap(Member::getVoiceState)
                        .flatMap(VoiceState::getChannel)
                        // Perform the connection
                        .flatMap(channel ->  {
                            // Send the message that we're connectiong
                            message.getChannel().flatMap(textChannel -> {
                                return textChannel.createMessage(
                                        "Connecting at `" +
                                        channel.getName() + "`!"
                                );
                            })
                            .subscribe();
                            return channel.join(spec -> spec.setProvider(GuildAudioManager.of(channel.getGuildId()).getProvider()))
                                    // Leave automatically if the bot is alone.
                                    .flatMap(connection -> {
                                        // The bot itself has a VoiceState; 1 VoiceState signals bot is alone
                                        final Publisher<Boolean> voiceStateCounter = channel.getVoiceStates()
                                                .count()
                                                .map(count -> 1L == count);

                                        // Get the Audio Manager
                                        Snowflake playerID = channel.getGuildId();
                                        GuildAudioManager audioManager = GuildAudioManager.of(playerID);

                                        // Boolean publisher to tell when there's nothing playing
                                        Publisher<Boolean> isPlaying = channel.getVoiceStates()
                                                .map(currPlaying -> audioManager.getPlayer().getPlayingTrack() == null);

                                        // After 10 seconds, check that music is playing. If not, then we leave the channel.
                                        final Mono<Void> onDelay = Mono.delay(Duration.ofSeconds(15L))
                                                .filterWhen(filler -> isPlaying)
                                                .switchIfEmpty(Mono.never())
                                                .then();

                                        // As people join and leave `channel`, check if the bot is alone.
                                        // Note the first filter is not strictly necessary, but it does prevent many unnecessary cache calls
                                        final Mono<Void> onEvent = channel.getClient().getEventDispatcher().on(VoiceStateUpdateEvent.class)
                                                .filter(agEvent -> agEvent.getOld().flatMap(VoiceState::getChannelId).map(channel.getId()::equals).orElse(false))
                                                .filterWhen(ignored -> voiceStateCounter)
                                                .next()
                                                .then();

                                        // Disconnect the bot if either onDelay or onEvent are completed!
                                        return Mono.first(onDelay, onEvent).
                                                doOnSuccess(test -> audioManager.getPlayer().destroy())
                                                .then(connection.disconnect());
                                                /*
                                                .doOnNext(test -> {
                                                    // Send the message that we're leaving
                                                    message.getChannel().flatMap(textChannel -> {
                                                                return textChannel.createMessage(
                                                                        "Goodbye!"
                                                                );
                                                            })
                                                            .subscribe();
                                                    audioManager.getPlayer().destroy();
                                                    connection.disconnect();
                                                }).then(); */
                                    });
                        })
                        .subscribe();


                    })
                .then());


        // PLAY
        // Constructs a command to play a given song
        commands.put("play ", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(command -> {
                    try {
                        // Get the url from the second line provided in the command
                        String audioUrl = command.getContent().substring(command.getContent().indexOf(" ") + 1);

                        // Get the guild ID for targeting
                        Snowflake snowflake =  command.getGuildId().orElseThrow(RuntimeException::new);

                        // Audio manager for scheduler manipulation
                        GuildAudioManager audioManager = GuildAudioManager.of(snowflake);

                        // Check that our URL is a URL afterall, and if not we search for it
                        if (!isUrl(audioUrl)) {
                            audioUrl = "ytsearch:" + audioUrl;
                        }

                        // Grab the scheduler and then load it with the given URL
                        TrackScheduler scheduler = audioManager.getScheduler();

                        // Create a new load requeuest using a new handler that overrides the usual so we can access
                        // both the channel and the track for parsing.
                        String finalAudioUrl = audioUrl;
                        PLAYER_MANAGER.loadItemOrdered(audioManager, audioUrl, new AudioLoadResultHandler() {
                            @Override
                            public void trackLoaded(AudioTrack track) {
                                scheduler.queue(track);

                                command.getChannel().flatMap(channel -> {
                                    String message = command.getUserData().username() +
                                            " added `" +
                                            track.getInfo().title +
                                            "` by `" +
                                            track.getInfo().author +
                                            "` to the queue!";
                                    return channel.createMessage(message);
                                }).subscribe();
                            }
                            // Override for playlists
                            @Override
                            public void playlistLoaded(AudioPlaylist playlist) {
                                if (playlist.isSearchResult()){
                                    trackLoaded(playlist.getTracks().get(0));
                                }
                                else {
                                    final List<AudioTrack> tracks = playlist.getTracks();

                                    command.getChannel().flatMap(channel -> {
                                        String message = command.getUserData().username() +
                                                " added `" +
                                                String.valueOf(tracks.size()) +
                                                "` tracks from the playlist `" +
                                                playlist.getName() +
                                                "` to the queue!";
                                        return channel.createMessage(message);
                                    }).subscribe();

                                    for (final AudioTrack track : tracks) {
                                        audioManager.getScheduler().queue(track);
                                    }
                                }

                            }

                            @Override
                            public void noMatches() {
                                command.getChannel().flatMap(channel -> {
                                    String message = "Unable to find a match for " + finalAudioUrl;
                                    return channel.createMessage(message);
                                }).subscribe();
                            }

                            @Override
                            public void loadFailed(FriendlyException exception) {
                                command.getChannel().flatMap(channel -> {
                                    String message = "Unable to load " + finalAudioUrl + " if it is age restricted, " +
                                            "this cannot be helped. Sorry.";
                                    return channel.createMessage(message);
                                }).subscribe();
                            }
                        });
                    } catch (Exception E) {
                        // If that doesn't work then we tell the user they put it in wrong
                        command.getChannel().flatMap(message ->
                                message.createMessage("Invalid input! Give a valid youtube, soundcloud, or bandcamp url!"))
                                .subscribe();
                    }


                })
                .then());

        // LEAVE
        // Constructs a command to get the bot to leave the channel
        commands.put("leave", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                    // Get the guild ID
                    Snowflake guildID = message.getGuildId().orElseThrow(RuntimeException::new);
                    // Get the audio manager
                    GuildAudioManager audioManager = GuildAudioManager.of(guildID);
                    // Clear schedule + destroy player
                    audioManager.getScheduler().clear();
                    audioManager.getPlayer().destroy();

                    // Find the current user's voice channel and disconnect from it
                    message.getAuthorAsMember()
                            .flatMap(Member::getVoiceState)
                            .flatMap(VoiceState::getChannel)
                            .flatMap(channel ->  channel.sendDisconnectVoiceState())
                                    .subscribe();

                    // Send the message that we're leaving
                    message.getChannel().flatMap(textChannel -> {
                        return textChannel.createMessage(
                                "Goodbye!"
                        );
                    })
                    .subscribe();
                })
                .then());

        // SKIP
        // Skips the currently playing song and moves on to the next.
        // If there is no next song, stops playing altogether.
        commands.put("skip", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                    Snowflake guildID = message.getGuildId().orElseThrow(RuntimeException::new);
                    GuildAudioManager audioManager = GuildAudioManager.of(guildID);
                    // Find the current user's voice channel and skip the currently playing song.
                    if (audioManager.getPlayer().getPlayingTrack() != null) {
                        message.getAuthorAsMember()
                                .flatMap(Member::getVoiceState)
                                .flatMap(VoiceState::getChannel)
                                .doOnNext(channel -> {

                                    // Tell them the skip is happening
                                    String output = "Skipping `" + audioManager.getPlayer().getPlayingTrack().getInfo().title + "`";
                                    message.getChannel().flatMap(textChannel -> textChannel.createMessage(output)).subscribe();

                                    // Perform a skip which returns a boolean value.
                                    // If that value is false, then we need to destroy the player because we're out of songs.
                                    if (!audioManager.getScheduler().nextTrack()) {
                                        audioManager.getPlayer().destroy();
                                    }
                                })
                                .subscribe();
                    } else{
                        String output = "Unable to skip!";
                        message.getChannel().flatMap(textChannel -> textChannel.createMessage(output)).subscribe();
                    }

                })
                .then());

        // NOW PLAYING
        // Spits out the currently playing song in discord
        commands.put("np", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                        // Get the guild ID
                        Snowflake guildID = message.getGuildId().orElseThrow(RuntimeException::new);

                        // Enter the message's channel
                        message.getChannel().flatMap(channel -> {
                            String nowPlaying = "Placeholder";
                            try {
                                // Get the currently playing track
                                AudioTrack currTrack = GuildAudioManager.of(guildID).getPlayer().getPlayingTrack();

                                // Get the current position in the track as a string
                                // Create a duration to convert to seconds properly for string formatting
                                Duration currDurr = Duration.ofMillis(currTrack.getPosition());
                                long currSTimeSeconds = currDurr.getSeconds();
                                String currSTime = formatTime(currSTimeSeconds);

                                nowPlaying = "Now playing: `" +
                                        currTrack.getInfo().title + "`\nBy: `" +
                                        currTrack.getInfo().author + "`\nAt: `" +
                                        currSTime +  "` / `" + formatTime(currTrack.getDuration())+
                                        "`\nFound At: " +
                                        currTrack.getInfo().uri + "\n";
                            } catch (Exception e) {
                                return channel.createMessage(
                                        nowPlaying = "There's nothing playing right now..."
                                );
                            }
                            return channel.createMessage(nowPlaying);
                            })
                            .subscribe(); // Necessary for nested streams.
                        }

                )
                .then());

        // PAUSE
        // Halts the currently playing audio
        commands.put("pause", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(channel -> {
                    // Get the server ID
                    Snowflake guildID = channel.getGuildId().orElseThrow(RuntimeException::new);
                    // Point to the correct audioManager for the channel
                    GuildAudioManager audioManager = GuildAudioManager.of(guildID);
                    // Set the pause status to true
                    audioManager.getPlayer().setPaused(true);
                    // Send a message telling them the song has been skipped
                    channel.getChannel()
                            .flatMap(message -> {

                                return message.createMessage(
                                        "Track paused!"
                                );
                            })
                            .subscribe();
                })
                .then());



        // RESUME
        // Resumes the audio if it was previously paused
        commands.put("resume", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                    // Get the server ID
                    Snowflake guildID = message.getGuildId().orElseThrow(RuntimeException::new);
                    // Point to the correct audioManager for the channel
                    GuildAudioManager audioManager = GuildAudioManager.of(guildID);
                    // Set the pause status to true
                    audioManager.getPlayer().setPaused(false);
                    // Send a message telling them the song has been skipped
                    message.getChannel()
                            .flatMap(channel -> {

                                return channel.createMessage(
                                        "Track resumed!"
                                );
                            })
                            .subscribe();
                })
                .then());

        // SEEK
        // Sets the currently playing song's time to a user given value
        commands.put("seek ", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                    // Get the guild ID
                    Snowflake guildID = message.getGuildId().orElseThrow(RuntimeException::new);

                    // Get the audioPlayer
                    GuildAudioManager guildAudio = GuildAudioManager.of(guildID);

                    // Get the new position
                    String givenPosition = message.getContent().split(" ")[1];
                    // Create a Duration for this time
                    Long seekTime = Long.valueOf(0);

                    // Checks that they gave us a valid input
                    boolean isLong = false;
                    try {
                        seekTime = Long.valueOf(givenPosition.split(":")[0]);
                        isLong = true;
                    } catch (Exception e) {
                        isLong = false;
                    }

                    // Counter to see if it's hours:minutes:seconds or minutes:seconds
                    int minSec = 0;

                    // Check that we have the right amount of inputs first
                    if ( ( givenPosition.split(":").length == 2 || givenPosition.split(":").length == 3 ) && isLong)
                    {
                        // While loop that gives us our seek time in milliseconds for the command
                        while (minSec < givenPosition.split(":").length  ) {

                            // Case for hours
                            System.out.println(givenPosition.split(":")[minSec]);
                            if (minSec - givenPosition.split(":").length == -3) {
                                // Add the hours as miliseconds to the seekTime
                                seekTime = seekTime + Long.valueOf(givenPosition.split(":")[minSec]) * 3600000L;

                            }

                            // Case for minutes
                            if (minSec - givenPosition.split(":").length == -2) {
                                // Add the minutes as miliseconds to the seekTime
                                seekTime = seekTime + Long.valueOf(givenPosition.split(":")[minSec]) * 60000L;
                            }

                            // Case for seconds
                            if (minSec - givenPosition.split(":").length == -1) {
                                // Add the seconds as miliseconds to the seekTime
                                seekTime = seekTime + Long.valueOf(givenPosition.split(":")[minSec]) * 1000L;
                            }

                            minSec++;
                        }

                        // Perform the seek
                        guildAudio.getPlayer().getPlayingTrack().setPosition(seekTime);

                        // Report in
                        message.getChannel()
                                .flatMap(channel -> {
                                    return channel.createMessage(
                                            "Seeking to `" + givenPosition + "`"
                                    );
                                })
                                .subscribe();
                    }
                    // Else we tell them to do it right
                    else{
                        message.getChannel()
                                .flatMap(channel -> {
                                    return channel.createMessage(
                                            "Couldn't seek to that position. Try `Hours:Minutes:Seconds` or `Minutes:Seconds` " +
                                            "with numeric values in the appropriate places."
                                    );
                                })
                                .subscribe();
                    }

                })
                .then());

        // QUEUE
        // Show the queue of songs that are coming next
        commands.put("queue", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                    // Get the guild ID
                    Snowflake guildID = message.getGuildId().orElseThrow(RuntimeException::new);

                    // Grab the audio manager
                    GuildAudioManager guildAudio = GuildAudioManager.of(guildID);

                    // Grab the queue
                    BlockingQueue<AudioTrack> queue = guildAudio.getScheduler().queue;

                    message.getChannel()
                            .flatMap(channel -> {
                                // Create our initial return message
                                String returnMessage = "";

                                // If the queue is empty return this message
                                if (queue.isEmpty()) {
                                    returnMessage = "The queue is currently empty";
                                }
                                // Else create our queue message with a maximum of 20 items to prevent overflow in discord.
                                else {
                                    final List<AudioTrack> trackList = new ArrayList<>(queue);
                                    final int trackCount = Math.min(queue.size(), 20);

                                    for (int i = 0; i < trackCount; i++){
                                        final AudioTrack track = trackList.get(i);
                                        final AudioTrackInfo info = track.getInfo();

                                        returnMessage = returnMessage + "#" +
                                                (i + 1) +
                                                " `" + info.title + "` " +
                                                " by `" +
                                                info.author + "` [`" +
                                                formatTime(track.getDuration()) + "`]\n";
                                    }

                                    if (trackList.size() > trackCount) {
                                        returnMessage = returnMessage + " and `" +
                                                String.valueOf(trackList.size() - trackCount) + "` more...";
                                    }
                                }
                                return channel.createMessage(returnMessage);

                            }).subscribe();
                })
                .then());

        // REMOVE
        // Removes an item from the queue at a given address
        commands.put("remove ", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                    // Create our initial return string
                    String returnMessage = "";

                    // Get the guild ID
                    Snowflake guildID = message.getGuildId().orElseThrow(RuntimeException::new);

                    // Get the player
                    GuildAudioManager audioManager = GuildAudioManager.of(guildID);

                    //Get the scheduler
                    TrackScheduler scheduler = audioManager.getScheduler();

                    // Make a list we can easily get to the value of
                    List<AudioTrack> trackList = new ArrayList<>(scheduler.queue);

                    try {
                        // Get our track
                        AudioTrack target = trackList.get(Integer.valueOf(message.getContent().split(" ")[1]) - 1);

                        if (scheduler.queue.remove(target)) {
                            returnMessage = "Removed `" + target.getInfo().title +
                                    "` from the queue!";
                        } else {
                            returnMessage = "Unable to remove `" + target.getInfo().title + "` from the queue...";
                        }

                    } catch (Exception e) {
                        returnMessage = "Invalid Command. Try remove followed by the integer position of the song in the queue";
                    }

                    // Send our result over discord
                    String finalMessage = returnMessage;
                    message.getChannel().flatMap(channel -> {
                        return channel.createMessage(finalMessage);
                    }).subscribe();

                })
                .then());

        // SHUFFLE
        // Shuffles the order of the queue
        commands.put("shuffle", event-> Mono.justOrEmpty(event.getMessage())
                .doOnNext(message -> {
                    // Initial return message
                    String returnMessage = "";

                    // Get the guild ID
                    Snowflake guildID = message.getGuildId().orElseThrow(RuntimeException::new);

                    // Grab the audio manager
                    GuildAudioManager guildAudio = GuildAudioManager.of(guildID);

                    try {
                        // Grab the queue
                        List<AudioTrack> oldQueue = new LinkedList(guildAudio.getScheduler().queue);

                        // Get the target size for the new queue
                        Integer remainingSongs = oldQueue.size();

                        // Shuffle the song order
                        for (int i = remainingSongs; i > 0; i--) {
                            // pick a random target
                            int randTargetNum = (int)Math.floor(Math.random()*(i)+0) ;

                            returnMessage = returnMessage + randTargetNum;
                            AudioTrack target = oldQueue.remove(randTargetNum);
                            returnMessage = returnMessage + target.getInfo().title + "\n";

                            // Remove it from the old list
                            guildAudio.getScheduler().queue.remove(target);

                            // Re add it in the new order
                            guildAudio.getScheduler().queue.add(target);
                        }
                        returnMessage = "Successfully shuffled the queue!";
                        if(remainingSongs == 0) returnMessage = "There's nothing to shuffle...";
                    } catch (Exception e) {
                        returnMessage = returnMessage + e.getMessage();
                        System.out.println("===============ERROR=================\n" + returnMessage);
                        returnMessage = "Could not shuffle the queue...";
                    }

                    // Send our result over discord
                    String finalMessage = returnMessage;
                    message.getChannel().flatMap(channel -> {
                        return channel.createMessage(finalMessage);
                    }).subscribe();

                }
        ).then());

        // PLAYLIST
        // TODO: Implement me
        // Takes the users snowflake and matches it to one in our database
        // Then searches for playlists available to them and loads that playlist into our bot
        // Essentially runs like a looped play command
        commands.put("playlist", event -> Mono.justOrEmpty(event.getMessage())
                .doOnNext(command -> {
                    try {
                        // Get the url from the second line provided in the command
                        String playlistID = command.getContent().substring(command.getContent().indexOf(" ") + 1);

                        // Get the guild ID for targeting
                        Snowflake snowflake =  command.getGuildId().orElseThrow(RuntimeException::new);

                        // Audio manager for scheduler manipulation
                        GuildAudioManager audioManager = GuildAudioManager.of(snowflake);

                        // Reconstruct the URL to point to the playlist we need
                        String protoURL = APIHead + "/playlists/" + playlistID + "/getsongs";
                        URL APIUrl = new URL(protoURL);



                        String dummyToken = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIxMjkwNDg1ODQ5ODQxOTkxNjgiLCJzdWIiOiJFYm9ueXRhbG9uIiwiaXNzIjoibGVtb24iLCJkaXNjcmltaW5hdG9yIjoiMTMzNyIsImlhdCI6MTY0MDE5MTM5NCwiZXhwIjoxNjQwMjc3Nzk0fQ.4buuLBcTWJCYyRqyS-HHtf7yWlto04GZ70_YxWABWNg";


                        // Open a connection with the API
                        HttpURLConnection con = (HttpURLConnection) APIUrl.openConnection();

                        // Sets the appropriate parameters to get in
                        con.setRequestMethod("GET");
                        con.setRequestProperty("Content-Type", "application/json");
                        con.setRequestProperty("Authorization", dummyToken);


                        // Get the response code and react accordingly
                        int status = con.getResponseCode();
                        if (status > 299) {
                            command.getChannel().flatMap(channel -> {
                                String message = "Sorry " + command.getUserData().username() +
                                        " we were unable to find this playlist with the id " + playlistID;
                                return channel.createMessage(message);
                            }).subscribe();
                        }
                        // Logic for success goes here...
                        else {
                            BufferedReader br = new BufferedReader(new InputStreamReader(
                                    (con.getInputStream())));
                            String output;
                            while ((output = br.readLine()) != null) {
                                String fOutput = output;

                                // Create the JSON object
                                JSONParser parse = new JSONParser();
                                JSONArray playlist = (JSONArray) parse.parse(output);

                                // Iterate through our result array, queuing each song into the player.
                                for(int i = 0; i < playlist.size() ; i++)
                                {
                                    // Get the individual's URL
                                    JSONObject playURL = (JSONObject) playlist.get(i);
                                    String audioUrl = playURL.getAsString("url");

                                    // Check that it's a valid URL and if not, search for it via lavaplayer
                                    if (!isUrl(audioUrl)) {
                                        audioUrl = "ytsearch:" + audioUrl;
                                    }
                                    // Check that our URL is a URL afterall, and if not we search for it
                                    if (!isUrl(audioUrl)) {
                                        audioUrl = "ytsearch:" + audioUrl;
                                    }

                                    // Grab the scheduler and then load it with the given URL
                                    TrackScheduler scheduler = audioManager.getScheduler();

                                    // Create a new load requeuest using a new handler that overrides the usual so we can access
                                    // both the channel and the track for parsing.
                                    String finalAudioUrl = audioUrl;
                                    PLAYER_MANAGER.loadItemOrdered(audioManager, audioUrl, new AudioLoadResultHandler() {
                                        @Override
                                        public void trackLoaded(AudioTrack track) {
                                            scheduler.queue(track);

                                            command.getChannel().flatMap(channel -> {
                                                String message = command.getUserData().username() +
                                                        " added `" +
                                                        track.getInfo().title +
                                                        "` by `" +
                                                        track.getInfo().author +
                                                        "` to the queue!";
                                                return channel.createMessage(message);
                                            }).subscribe();
                                        }
                                        // Override for playlists
                                        @Override
                                        public void playlistLoaded(AudioPlaylist playlist) {
                                            if (playlist.isSearchResult()){
                                                trackLoaded(playlist.getTracks().get(0));
                                            }
                                            else {
                                                final List<AudioTrack> tracks = playlist.getTracks();

                                                command.getChannel().flatMap(channel -> {
                                                    String message = command.getUserData().username() +
                                                            " added `" +
                                                            String.valueOf(tracks.size()) +
                                                            "` tracks from the playlist `" +
                                                            playlist.getName() +
                                                            "` to the queue!";
                                                    return channel.createMessage(message);
                                                }).subscribe();

                                                for (final AudioTrack track : tracks) {
                                                    audioManager.getScheduler().queue(track);
                                                }
                                            }

                                        }

                                        @Override
                                        public void noMatches() {
                                            command.getChannel().flatMap(channel -> {
                                                String message = "Unable to find a match for " + finalAudioUrl;
                                                return channel.createMessage(message);
                                            }).subscribe();
                                        }

                                        @Override
                                        public void loadFailed(FriendlyException exception) {
                                            command.getChannel().flatMap(channel -> {
                                                String message = "Unable to load " + finalAudioUrl + " if it is age restricted, " +
                                                        "this cannot be helped. Sorry.";
                                                return channel.createMessage(message);
                                            }).subscribe();
                                        }
                                    });

                                }

                            }

                        }
                        con.disconnect();
                    } catch (MalformedURLException E) {
                        // If that doesn't work then we tell the user they put it in wrong
                        command.getChannel().flatMap(message ->
                                        message.createMessage("There was an issue with the api... sorry!"))
                                .subscribe();
                    }
                    catch (Exception E){
                        // If that doesn't work then we tell the user they put it in wrong
                        command.getChannel().flatMap(message ->
                                        message.createMessage("Unable to play... sorry!\n" + E.getMessage()))
                                .subscribe();
                    }


                })
                .then());


        // ########################### CONNECTION LOGIC ##################################
        // Creates our connection and stops the code from running until the bot is logged in.
        // Builds the client
        final GatewayDiscordClient client =DiscordClientBuilder.create(bToken).build().login().block();



        // ########################## COMMAND LISTENER $##################################
        try {
            client.getEventDispatcher().on(MessageCreateEvent.class)
                    // 3.1 Message.getContent() is a String
                    .flatMap(event -> Mono.just(event.getMessage().getContent())
                            .flatMap(content -> Flux.fromIterable(commands.entrySet())
                                    // We will be using ! as our "prefix" to any command in the system.
                                    .filter(entry -> content.toLowerCase().startsWith('&' + entry.getKey()))
                                    .flatMap(entry -> entry.getValue().execute(event))
                                    .next()))
                    .subscribe();
        } catch (Exception e) {
            client.getEventDispatcher().on(MessageCreateEvent.class)
                            .flatMap(event -> Mono.just(event.getMessage())
                                    .doOnNext(message -> {
                                        message.getChannel().doOnNext(channel -> {
                                            channel.createMessage("Error! Something went wrong... restarting...");
                                        }).subscribe();
                                    })).next();
            System.out.println(e.getMessage());
        }




        // ########################### DISCONNECT ########################################
        // This closes the bot when disconnected.
        client.onDisconnect().block();
    }
}
