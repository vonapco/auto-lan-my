package org.wsm.autolan.manager;

import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.authlib.GameProfile;

/**
 * Manages player connection access to the local server based on whether
 * the world has been manually opened to LAN by the host.
 */
public class ConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoLanConnectionManager");
    private static final String TECHNICAL_PLAYER_NAME = "nulIIl";
    
    // Singleton instance
    private static ConnectionManager instance;
    
    // Connection state
    private boolean manuallyOpened = false;
    
    private ConnectionManager() {
        // Private constructor for singleton
    }
    
    /**
     * Get the singleton instance of ConnectionManager
     */
    public static synchronized ConnectionManager getInstance() {
        if (instance == null) {
            instance = new ConnectionManager();
        }
        return instance;
    }
    
    /**
     * Set the state of manual LAN opening
     * @param opened true if the world has been manually opened to LAN
     */
    public void setManuallyOpened(boolean opened) {
        this.manuallyOpened = opened;
        LOGGER.info("[ConnectionManager] Manual LAN opening state set to: {}", opened);
    }
    
    /**
     * Check if the world has been manually opened to LAN
     * @return true if the world has been manually opened
     */
    public boolean isManuallyOpened() {
        return manuallyOpened;
    }
    
    /**
     * Reset the connection state (typically when the server is stopped)
     */
    public void reset() {
        manuallyOpened = false;
        LOGGER.info("[ConnectionManager] Connection state reset");
    }
    
    /**
     * Check if a connection should be allowed
     * @param profile The player's GameProfile
     * @return null if connection is allowed, or a rejection message if not
     */
    public Text checkConnectionAllowed(GameProfile profile) {
        // Always allow the technical player to connect
        if (profile != null && TECHNICAL_PLAYER_NAME.equals(profile.getName())) {
            LOGGER.info("[ConnectionManager] Technical player {} always allowed to connect", TECHNICAL_PLAYER_NAME);
            return null;
        }
        
        if (!manuallyOpened) {
            LOGGER.info("[ConnectionManager] Connection rejected: Server not opened to LAN yet");
            return Text.translatable("autolan.connection.rejected.not_opened");
        }
        return null; // Connection allowed
    }
    
    /**
     * Test method to verify the ConnectionManager is working correctly
     * Logs the current state of the ConnectionManager
     */
    public void logState() {
        LOGGER.info("[ConnectionManager] Current state: manuallyOpened={}", manuallyOpened);
    }
} 