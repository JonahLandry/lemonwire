# Lemonwire
This project creates a bot that runs on discord and plays songs from youtube, soundcloud, or bandcamp. 

It can also run playlists created in the Lemonwire Website ( LINK HERE ) via playlist IDs.


# Commands
join 	- Joins the sender's voice channel

leave 	- Leaves the sender's voice channel

play 	- Plays a song designated by a url. Also works with youtube playlists.
	- SYNTAX: "&play [target url]"

skip	- Skips the song that's currently playing

np	- Displays the currently playing song's details.

pause	- Pauses the current player

resume	- Resumes playing the current music

seek	- Seeks to a specified position in the currently playing song
	- SYNTAX: "&seek ([HH:MM:SS]/[MM:SS])"

queue	- Displays the next 20 songs to be played (Limited by discord's character limit in the current state)

remove	- Removes a song at the given index in the queue. You can see these in the &queue command
	- SYNTAX: "&remove [QueuePosition] 

shuffle	- Shuffles the queue of currently playing music

playlist- Given a playlist ID from the website, queues that entire playlist
	- SYNTAX: "&playlist [playlistID]"

# Setup
Bot is invited to servers on a need to know basis. You gotta know a guy. If you want to use the code to make your own bot, feel free, but you will need to send in the bot's token in the arguments of the botDriver class.
