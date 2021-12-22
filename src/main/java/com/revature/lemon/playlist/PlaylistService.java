package com.revature.lemon.playlist;

import com.revature.lemon.common.exceptions.PlaylistNotFoundException;
import com.revature.lemon.common.exceptions.ResourceNotFoundException;
import com.revature.lemon.common.model.SongPlaylist;
import com.revature.lemon.common.util.AccessType;
import com.revature.lemon.playlist.dtos.requests.AddSongRequest;
import com.revature.lemon.playlist.dtos.requests.AddUserRequest;
import com.revature.lemon.playlist.dtos.requests.NewPlaylistRequest;
import com.revature.lemon.playlist.dtos.requests.RemoveUserRequest;
import com.revature.lemon.playlist.dtos.responses.PlaylistResponse;
import com.revature.lemon.playlist.dtos.responses.SongsInPlaylistResponse;
import com.revature.lemon.playlist.dtos.responses.UsersInPlaylistResponse;
import com.revature.lemon.song.Song;
import com.revature.lemon.song.SongRepository;
import com.revature.lemon.user.User;
import com.revature.lemon.user.UserRepository;
import com.revature.lemon.userplaylist.UserPlaylist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final UserRepository userRepository;
    private final SongRepository songRepository;

    @Autowired
    public PlaylistService (PlaylistRepository playlistRepository, UserRepository userRepository, SongRepository songRepository) {
        this.playlistRepository = playlistRepository;
        this.userRepository = userRepository;
        this.songRepository = songRepository;
    }

    /**
     * Turns the playlistRequest into a playlist to be persisted to the database
     */
    public PlaylistResponse createNewPlaylist(@Valid NewPlaylistRequest playlistRequest) {

        Playlist playlist = new Playlist(playlistRequest);
        playlist.setId(UUID.randomUUID().toString());
        playlistRepository.save(playlist);
        return new PlaylistResponse(playlist);
    }

    /**
     * Grabs the list of songs from newSongRequest and maps it to an associate table between songs and playlist.
     * Set that table to playlist then save
     * @param newSongRequest contains a playlistId and a list of songs
     */
    public void addSongInPlaylist(AddSongRequest newSongRequest) {

        //load up the playlist
        Playlist playlist = playlistRepository.findById(newSongRequest.getPlaylistId())
                                              .orElseThrow(PlaylistNotFoundException::new);
        Song song = new Song();
        song.setUrl(newSongRequest.getSongUrl());
        song.setName(newSongRequest.getName());
        song.setDuration(newSongRequest.getDuration());
        //checks if song exists, if not save it to the database before playlist adds it
        songRepository.findById(newSongRequest.getSongUrl()).orElse(songRepository.save(song));


        SongPlaylist songPlaylist = new SongPlaylist();
        songPlaylist.setSong(song);
        playlist.addSong(songPlaylist);

        playlistRepository.save(playlist);
    }

    /**
     * @return all playlists listed as "PUBLIC"
     */
    public List<PlaylistResponse> getPublicPlaylists() {

        return playlistRepository.findPlaylistByAccess(AccessType.PUBLIC)
                                 .stream()
                                 .map(PlaylistResponse::new)
                                 .collect(Collectors.toList());
    }

    /**
     * @param userId id of the current user
     * @return all playlists listed as "PRIVATE" that the current user has access to
     */
    public List<PlaylistResponse> getPrivatePlaylists(String userId) {

        return playlistRepository.findPlaylistByAccessAndId(AccessType.PRIVATE, userId)
                                 .stream()
                                 .map(PlaylistResponse::new)
                                 .collect(Collectors.toList());
    }

    /**
     * Delete playlist of the given playlistId
     * @param playlistId id of the playlist to be deleted
     */
    public void deletePlaylist(String playlistId) {

        playlistRepository.deleteById(playlistId);
    }

    // Currently, this works... it just needs a way to get the particular user's ID which is in the users table; We are in the playlistService class

    /**
     * Uses the given username and discriminator to get the id then remove that
     * @param playlistId the id of the current playlist
     * @param newUser contains new user's username, discriminator, and granted role
     */
    public void addUserToPlaylist(String playlistId, AddUserRequest newUser) {

        Playlist playlist = playlistRepository.findById(playlistId)
                                              .orElseThrow(PlaylistNotFoundException::new);

        User user = userRepository.findUserByUsernameAndDiscriminator(newUser.getUsername(), newUser.getDiscriminator()).orElseThrow(ResourceNotFoundException::new);

        playlist.addUser(user, newUser.getUserRole());
        playlistRepository.save(playlist);
    }

    /**
     * Edit role of user that has access to playlist
     * @param playlistId is the id of the current playlist
     * @param updateUser contains the username, discriminator, and new user role of the user to be updated
     */
    public void editUserRoleInPlaylist(String playlistId, AddUserRequest updateUser) {

        Playlist playlist = playlistRepository.findById(playlistId)
                                              .orElseThrow(PlaylistNotFoundException::new);

        playlist.getUserRoleList()
                .stream()
                .filter(e -> e.getUser().getUsername().equals(updateUser.getUsername()))
                .filter(e -> e.getUser().getDiscriminator().equals(updateUser.getDiscriminator()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("User did not have any roles in this playlist"))
                .setUserRole(updateUser.getUserRole());
        playlistRepository.save(playlist);
    }

    /**
     * Looks through the playlist's UserPlaylist and checks to see if any of those contain the given username and discriminator
     * @param playlistId the id of the playlist we are currently looking at
     * @param userRequest contains the username and discriminator of the user to be removed from the playlist
     */
    public void removeUserFromPlaylist(String playlistId, RemoveUserRequest userRequest) {

        Playlist playlist = playlistRepository.findById(playlistId)
                                              .orElseThrow(PlaylistNotFoundException::new);

        UserPlaylist userPlaylist = playlist.getUserRoleList()
                                            .stream()
                                            .filter(e -> e.getUser().getUsername().equals(userRequest.getUsername()))
                                            .filter(e -> e.getUser().getDiscriminator().equals(userRequest.getDiscriminator()))
                                            .findFirst()
                                            .orElseThrow(() -> new ResourceNotFoundException("User did not have any roles in this playlist"));

        playlist.removeUser(userPlaylist);
        playlistRepository.save(playlist);
    }

    /**
     * Get all the songs that this playlist contains
     * @param playlistId id of the playlist we are querying
     * @return a list of songs that the playlist has
     */
    public List<SongsInPlaylistResponse> getSongsInPlaylist(String playlistId) {

        return playlistRepository.findPlaylistByIdOrderBySongOrderListSongOrder(playlistId)
                                 .orElseThrow(PlaylistNotFoundException::new)
                                 .getSongOrderList()
                                 .stream()
                                 .map(SongsInPlaylistResponse::new)
                                 .collect(Collectors.toList());
    }

    /**
     * Get all the users that have a role in the particular playlist
     * @param playlistId id of the playlist we are querying
     * @return a list of users that has a role to this playlist
     */
    public List<UsersInPlaylistResponse> getUsersWithPlaylistAccess(String playlistId) {

        return playlistRepository.findById(playlistId)
                                 .orElseThrow(PlaylistNotFoundException::new)
                                 .getUserRoleList()
                                 .stream()
                                 .map(UsersInPlaylistResponse::new)
                                 .collect(Collectors.toList());
    }

}
