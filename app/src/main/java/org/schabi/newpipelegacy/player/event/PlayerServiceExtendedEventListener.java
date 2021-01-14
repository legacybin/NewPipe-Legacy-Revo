package org.schabi.newpipelegacy.player.event;

import org.schabi.newpipelegacy.player.MainPlayer;
import org.schabi.newpipelegacy.player.Player;

public interface PlayerServiceExtendedEventListener extends PlayerServiceEventListener {
    void onServiceConnected(Player player,
                            MainPlayer playerService,
                            boolean playAfterConnect);
    void onServiceDisconnected();
}
