package com.mohistmc.youer.bukkit.pluginfix;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Runtime hook to initialize MythicDungeons fixes
 */
public class MythicDungeonsHook {
    
    private static boolean initialized = false;
    
    static {
        // Static initializer - runs when class is first loaded
        scheduleInitialization();
    }
    
    public static void scheduleInitialization() {
        if (initialized) return;
        
        // Try to initialize immediately if server is ready
        if (Bukkit.getServer() != null && Bukkit.getPluginManager() != null) {
            tryInitialize();
        }
        
        // Also schedule delayed initialization
        try {
            new Thread(() -> {
                for (int i = 0; i < 60; i++) { // Try for 60 seconds
                    try {
                        Thread.sleep(1000);
                        if (tryInitialize()) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }).start();
        } catch (Exception e) {
            System.err.println("[MythicDungeonsHook] Failed to schedule initialization: " + e.getMessage());
        }
    }
    
    private static synchronized boolean tryInitialize() {
        if (initialized) return true;
        
        try {
            // Check if server is ready
            if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null) {
                return false;
            }
            
            // Check for MythicDungeons
            Plugin md = Bukkit.getPluginManager().getPlugin("MythicDungeons");
            if (md == null || !md.isEnabled()) {
                return false;
            }
            
            // Initialize command fix
            MythicDungeonsCommandFix.initialize();
            
            System.out.println("[MythicDungeonsHook] Successfully initialized MythicDungeons fixes");
            initialized = true;
            
            // Also schedule periodic re-initialization in case of reload
            Plugin youer = Bukkit.getPluginManager().getPlugin("Youer");
            if (youer == null) {
                youer = md; // Use MythicDungeons as fallback
            }
            
            if (youer != null) {
                final Plugin plugin = youer;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        // Re-check and re-initialize if needed
                        if (!initialized || Bukkit.getPluginManager().getPlugin("MythicDungeons") != null) {
                            MythicDungeonsCommandFix.initialize();
                        }
                    }
                }.runTaskTimer(plugin, 20L * 60, 20L * 60); // Check every minute
            }
            
            return true;
            
        } catch (Exception e) {
            System.err.println("[MythicDungeonsHook] Error during initialization: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Force initialization - can be called manually
     */
    public static void forceInitialize() {
        initialized = false;
        tryInitialize();
    }
}
