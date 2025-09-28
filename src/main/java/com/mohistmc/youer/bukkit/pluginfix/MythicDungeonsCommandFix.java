package com.mohistmc.youer.bukkit.pluginfix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MythicDungeons Command Fix - Intercepts /md play and fixes teleportation
 */
public class MythicDungeonsCommandFix implements Listener {
    
    private static final Map<String, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private static boolean initialized = false;
    
    static class PendingTeleport {
        final Player player;
        final String dungeonName;
        final long timestamp;
        int attempts;
        
        PendingTeleport(Player player, String dungeonName) {
            this.player = player;
            this.dungeonName = dungeonName;
            this.timestamp = System.currentTimeMillis();
            this.attempts = 0;
        }
    }
    
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        
        try {
            Plugin youer = Bukkit.getPluginManager().getPlugin("Youer");
            if (youer != null) {
                Bukkit.getPluginManager().registerEvents(new MythicDungeonsCommandFix(), youer);
                System.out.println("[MythicDungeonsCommandFix] Initialized command interceptor");
            }
        } catch (Exception e) {
            System.err.println("[MythicDungeonsCommandFix] Failed to initialize: " + e.getMessage());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase();
        
        // Intercept /md play command
        if (msg.startsWith("/md play ") || msg.startsWith("/mythicdungeons:md play ")) {
            Player player = event.getPlayer();
            String[] parts = msg.split(" ");
            
            if (parts.length >= 3) {
                String dungeonName = parts[2];
                System.out.println("[MythicDungeonsCommandFix] Intercepted /md play command for " + player.getName() + " - Dungeon: " + dungeonName);
                
                // Store pending teleport
                pendingTeleports.put(player.getName(), new PendingTeleport(player, dungeonName));
                
                // Start monitoring for world change
                startTeleportMonitor(player, dungeonName);
            }
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PendingTeleport pending = pendingTeleports.get(player.getName());
        
        if (pending != null) {
            World newWorld = player.getWorld();
            String worldName = newWorld.getName();
            
            System.out.println("[MythicDungeonsCommandFix] Player " + player.getName() + " changed world to: " + worldName);
            
            // Check if this is the dungeon world
            if (worldName.contains(pending.dungeonName) || worldName.contains("_")) {
                System.out.println("[MythicDungeonsCommandFix] Detected dungeon world, scheduling teleport fix");
                
                // Schedule immediate teleport fix
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        fixPlayerLocation(player, newWorld, pending.dungeonName);
                    }
                }.runTaskLater(getPlugin(), 5L); // Small delay to let world load
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        
        if (to != null) {
            World world = to.getWorld();
            if (world != null) {
                String worldName = world.getName();
                
                // Check if teleporting to dungeon world with bad coordinates
                if ((worldName.contains("dungeon") || worldName.contains("deneme") || worldName.contains("_")) &&
                    to.getY() < 0 || (to.getX() == 0 && to.getZ() == 0)) {
                    
                    System.out.println("[MythicDungeonsCommandFix] Detected bad teleport location, fixing...");
                    
                    // Cancel and reschedule with proper location
                    event.setCancelled(true);
                    
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Location fixed = findProperSpawnLocation(world);
                            if (fixed != null) {
                                player.teleport(fixed);
                                System.out.println("[MythicDungeonsCommandFix] Teleported to fixed location: " + 
                                    fixed.getBlockX() + ", " + fixed.getBlockY() + ", " + fixed.getBlockZ());
                            }
                        }
                    }.runTaskLater(getPlugin(), 1L);
                }
            }
        }
    }
    
    private static void startTeleportMonitor(Player player, String dungeonName) {
        new BukkitRunnable() {
            int checks = 0;
            
            @Override
            public void run() {
                checks++;
                
                World world = player.getWorld();
                String worldName = world.getName();
                
                // Check if player is in the dungeon world
                if (worldName.contains(dungeonName) || worldName.contains("_")) {
                    // Try to fix location
                    if (fixPlayerLocation(player, world, dungeonName)) {
                        pendingTeleports.remove(player.getName());
                        this.cancel();
                    } else if (checks >= 20) { // 10 seconds timeout
                        System.out.println("[MythicDungeonsCommandFix] Timeout fixing location for " + player.getName());
                        pendingTeleports.remove(player.getName());
                        this.cancel();
                    }
                } else if (checks >= 40) { // 20 seconds timeout
                    System.out.println("[MythicDungeonsCommandFix] Timeout waiting for world change for " + player.getName());
                    pendingTeleports.remove(player.getName());
                    this.cancel();
                }
            }
        }.runTaskTimer(getPlugin(), 10L, 10L); // Check every 0.5 seconds
    }
    
    private static boolean fixPlayerLocation(Player player, World world, String dungeonName) {
        Location currentLoc = player.getLocation();
        
        // Check if player is already in a good location
        if (currentLoc.getY() > 10 && currentLoc.getY() < 200 && 
            (Math.abs(currentLoc.getX()) > 1 || Math.abs(currentLoc.getZ()) > 1)) {
            System.out.println("[MythicDungeonsCommandFix] Player already in good location");
            return true;
        }
        
        System.out.println("[MythicDungeonsCommandFix] Player at bad location: " + 
            currentLoc.getBlockX() + ", " + currentLoc.getBlockY() + ", " + currentLoc.getBlockZ());
        
        // Try to find proper spawn
        Location spawn = findProperSpawnLocation(world);
        
        if (spawn != null) {
            player.teleport(spawn);
            System.out.println("[MythicDungeonsCommandFix] Fixed player location to: " + 
                spawn.getBlockX() + ", " + spawn.getBlockY() + ", " + spawn.getBlockZ());
            return true;
        }
        
        return false;
    }
    
    private static Location findProperSpawnLocation(World world) {
        // Method 1: Try to get from MythicDungeons instance
        Location mdSpawn = getSpawnFromMythicDungeons(world);
        if (mdSpawn != null) {
            return mdSpawn;
        }
        
        // Method 2: Scan for dungeon rooms
        Location scanned = scanForDungeonRoom(world);
        if (scanned != null) {
            return scanned;
        }
        
        // Method 3: Common procedural dungeon spawn points
        int[][] commonCoords = {
            {0, 65, 0},
            {8, 65, 8},
            {-8, 65, 8},
            {8, 65, -8},
            {16, 65, 0},
            {0, 65, 16}
        };
        
        for (int[] coords : commonCoords) {
            Location test = new Location(world, coords[0] + 0.5, coords[1], coords[2] + 0.5);
            if (isValidSpawnLocation(test)) {
                return test;
            }
        }
        
        // Method 4: Create emergency spawn
        return createEmergencySpawn(world);
    }
    
    private static Location getSpawnFromMythicDungeons(World world) {
        try {
            Plugin md = Bukkit.getPluginManager().getPlugin("MythicDungeons");
            if (md == null) return null;
            
            // Try reflection to get spawn
            Class<?> mdClass = md.getClass();
            
            // Try to get instance manager
            Method getInstanceManager = null;
            for (Method m : mdClass.getDeclaredMethods()) {
                if (m.getName().contains("Instance") && m.getParameterCount() == 0) {
                    getInstanceManager = m;
                    break;
                }
            }
            
            if (getInstanceManager != null) {
                getInstanceManager.setAccessible(true);
                Object instanceManager = getInstanceManager.invoke(md);
                
                if (instanceManager != null) {
                    // Try to get instance by world
                    Method getByWorld = null;
                    for (Method m : instanceManager.getClass().getDeclaredMethods()) {
                        if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == World.class) {
                            getByWorld = m;
                            break;
                        }
                    }
                    
                    if (getByWorld != null) {
                        getByWorld.setAccessible(true);
                        Object instance = getByWorld.invoke(instanceManager, world);
                        
                        if (instance != null) {
                            // Look for spawn field
                            for (Field f : instance.getClass().getDeclaredFields()) {
                                if (f.getType() == Location.class) {
                                    f.setAccessible(true);
                                    Location loc = (Location) f.get(instance);
                                    if (loc != null && loc.getY() > 0) {
                                        return loc.clone();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silent fail
        }
        
        return null;
    }
    
    private static Location scanForDungeonRoom(World world) {
        // Scan a grid pattern for dungeon rooms
        for (int x = -50; x <= 50; x += 10) {
            for (int z = -50; z <= 50; z += 10) {
                for (int y = 50; y <= 80; y += 5) {
                    Location test = new Location(world, x, y, z);
                    if (isValidSpawnLocation(test)) {
                        // Found a room, return center
                        return new Location(world, x + 0.5, y + 1, z + 0.5);
                    }
                }
            }
        }
        return null;
    }
    
    private static boolean isValidSpawnLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        
        try {
            // Load chunk if needed
            World world = loc.getWorld();
            int chunkX = loc.getBlockX() >> 4;
            int chunkZ = loc.getBlockZ() >> 4;
            
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                world.loadChunk(chunkX, chunkZ);
            }
            
            // Check for solid floor and air above
            boolean hasFloor = loc.clone().subtract(0, 1, 0).getBlock().getType().isSolid();
            boolean hasSpace = loc.getBlock().getType().isAir() && 
                              loc.clone().add(0, 1, 0).getBlock().getType().isAir();
            
            return hasFloor && hasSpace;
        } catch (Exception e) {
            return false;
        }
    }
    
    private static Location createEmergencySpawn(World world) {
        Location spawn = new Location(world, 0.5, 65, 0.5);
        
        // Create a platform
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.getBlockAt(x, 64, z).setType(org.bukkit.Material.STONE);
                world.getBlockAt(x, 65, z).setType(org.bukkit.Material.AIR);
                world.getBlockAt(x, 66, z).setType(org.bukkit.Material.AIR);
                world.getBlockAt(x, 67, z).setType(org.bukkit.Material.AIR);
            }
        }
        
        System.out.println("[MythicDungeonsCommandFix] Created emergency spawn platform");
        return spawn;
    }
    
    private static Plugin getPlugin() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Youer");
        if (p == null) p = Bukkit.getPluginManager().getPlugin("MythicDungeons");
        if (p == null && Bukkit.getPluginManager().getPlugins().length > 0) {
            p = Bukkit.getPluginManager().getPlugins()[0];
        }
        return p;
    }
}
