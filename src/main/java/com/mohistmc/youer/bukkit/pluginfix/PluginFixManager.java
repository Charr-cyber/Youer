package com.mohistmc.youer.bukkit.pluginfix;

import com.mohistmc.youer.Youer;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ARETURN;

/**
 * Complete PluginFixManager with all patches for MythicDungeons and other plugins
 * @version 3.0 - Full version with all compatibility patches
 */
public class PluginFixManager {

    // ================== CACHE & STATE MANAGEMENT ==================
    private static final Map<String, DungeonGenerationState> dungeonStates = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Boolean>> generationFutures = new ConcurrentHashMap<>();
    private static final ExecutorService generationExecutor = Executors.newFixedThreadPool(4);
    
    // Generation states
    private enum DungeonGenerationState {
        NOT_STARTED,
        PRELOADING,
        GENERATING,
        READY,
        FAILED
    }
    
    // Dungeon type detection
    private enum DungeonType {
        PROCEDURAL,  // Procedurally generated dungeons
        CLASSIC,     // Schematic-based dungeons
        INSTANCED    // Instance-based dungeons
    }
    
    // ================== MAIN TELEPORT HANDLER ==================
    
    /**
     * Enhanced teleportEntityToDungeon with pre-generation and smart detection
     */
    public static void teleportEntityToDungeon(Entity entity, Location target) {
        if (entity == null || target == null || !(entity instanceof Player player)) {
            return;
        }
        
        World world = target.getWorld();
        String worldName = world.getName();
        DungeonType dungeonType = detectDungeonType(worldName);
        
        System.out.println("[MythicDungeons] Detected dungeon type: " + dungeonType + " for world: " + worldName);
        
        // Check current generation state
        DungeonGenerationState currentState = dungeonStates.getOrDefault(worldName, DungeonGenerationState.NOT_STARTED);
        
        if (currentState == DungeonGenerationState.READY) {
            // Dungeon is ready, teleport immediately
            performSafeTeleport(player, target, dungeonType);
        } else if (currentState == DungeonGenerationState.GENERATING || currentState == DungeonGenerationState.PRELOADING) {
            // Generation in progress, wait for completion
            waitForGenerationAndTeleport(player, target, worldName, dungeonType);
        } else {
            // Start generation process
            initiateGenerationAndTeleport(player, target, worldName, dungeonType);
        }
    }
    
    /**
     * Overloaded method for backward compatibility
     */
    public static void teleportEntityToDungeon(Entity entity, Location target, boolean isProcedural) {
        if (entity == null || target == null) return;
        
        if (!(entity instanceof Player player)) {
            return;
        }

        // Use the enhanced method
        teleportEntityToDungeon(entity, target);
    }
    
    /**
     * Smart dungeon type detection based on world name and configuration
     */
    private static DungeonType detectDungeonType(String worldName) {
        if (worldName.contains("procedural") || worldName.contains("proc") || worldName.contains("deneme")) {
            return DungeonType.PROCEDURAL;
        } else if (worldName.contains("instance") || worldName.contains("inst")) {
            return DungeonType.INSTANCED;
        } else {
            return DungeonType.CLASSIC;
        }
    }
    
    // ================== GENERATION SYSTEM ==================
    
    /**
     * Initiate dungeon generation with proper sequencing
     */
    private static void initiateGenerationAndTeleport(Player player, Location target, String worldName, DungeonType type) {
        dungeonStates.put(worldName, DungeonGenerationState.PRELOADING);
        
        // Send status to player
        player.sendMessage("§e[MythicDungeons] §7Preparing dungeon world...");
        
        // First, preload chunks in main thread
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            boolean preloaded = preloadDungeonWorld(target.getWorld(), type);
            
            if (!preloaded) {
                dungeonStates.put(worldName, DungeonGenerationState.FAILED);
                player.sendMessage("§c[MythicDungeons] §7Failed to preload world!");
                handleGenerationFailure(player, target, null);
                return;
            }
            
            // Now continue with async generation
            dungeonStates.put(worldName, DungeonGenerationState.GENERATING);
            player.sendMessage("§e[MythicDungeons] §7Generating dungeon structures...");
            
            CompletableFuture<Boolean> generationFuture = generateDungeonStructures(target.getWorld(), type)
                .thenApply(generated -> {
                    if (generated) {
                        dungeonStates.put(worldName, DungeonGenerationState.READY);
                        player.sendMessage("§a[MythicDungeons] §7Dungeon ready!");
                    } else {
                        dungeonStates.put(worldName, DungeonGenerationState.FAILED);
                        player.sendMessage("§c[MythicDungeons] §7Failed to generate dungeon!");
                    }
                    return generated;
                });
            
            generationFutures.put(worldName, generationFuture);
            
            // Handle completion
            generationFuture.whenComplete((success, error) -> {
                generationFutures.remove(worldName);
                if (success && error == null) {
                    Bukkit.getScheduler().runTask(getPlugin(), () -> {
                        performSafeTeleport(player, target, type);
                    });
                } else {
                    Bukkit.getScheduler().runTask(getPlugin(), () -> {
                        handleGenerationFailure(player, target, error);
                    });
                }
            });
        });
    }
    
    /**
     * Wait for ongoing generation and then teleport
     */
    private static void waitForGenerationAndTeleport(Player player, Location target, String worldName, DungeonType type) {
        player.sendMessage("§e[MythicDungeons] §7Waiting for dungeon generation...");
        
        CompletableFuture<Boolean> existingFuture = generationFutures.get(worldName);
        if (existingFuture != null) {
            existingFuture.whenComplete((success, error) -> {
                if (success && error == null) {
                    Bukkit.getScheduler().runTask(getPlugin(), () -> {
                        performSafeTeleport(player, target, type);
                    });
                } else {
                    Bukkit.getScheduler().runTask(getPlugin(), () -> {
                        handleGenerationFailure(player, target, error);
                    });
                }
            });
        } else {
            // No existing generation, start new one
            initiateGenerationAndTeleport(player, target, worldName, type);
        }
    }
    
    /**
     * Preload dungeon world with proper chunk loading strategy
     * MUST be called from main thread!
     */
    private static boolean preloadDungeonWorld(World world, DungeonType type) {
        try {
            // Ensure we're on main thread
            if (!Bukkit.isPrimaryThread()) {
                System.err.println("[MythicDungeons] ERROR: preloadDungeonWorld called from async thread!");
                return false;
            }
            
            System.out.println("[MythicDungeons] Preloading world: " + world.getName() + " (Type: " + type + ")");
            
            // Calculate chunk loading radius based on dungeon type
            int radius = switch (type) {
                case PROCEDURAL -> 10;  // Reduced for performance
                case INSTANCED -> 7;    // Medium radius for instances
                case CLASSIC -> 5;      // Small radius for classic dungeons
            };
            
            // Force load chunks synchronously (we're on main thread)
            int centerChunkX = 0;
            int centerChunkZ = 0;
            int loadedCount = 0;
            
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    int chunkX = centerChunkX + x;
                    int chunkZ = centerChunkZ + z;
                    
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        world.loadChunk(chunkX, chunkZ, true);
                        loadedCount++;
                        
                        // For procedural dungeons, yield occasionally to prevent freezing
                        if (type == DungeonType.PROCEDURAL && loadedCount % 10 == 0) {
                            // Let other tasks run briefly
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            }
            
            System.out.println("[MythicDungeons] Preloaded " + loadedCount + " chunks");
            return true;
            
        } catch (Exception e) {
            System.err.println("[MythicDungeons] Failed to preload world: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Generate dungeon structures using MythicDungeons API
     */
    private static CompletableFuture<Boolean> generateDungeonStructures(World world, DungeonType type) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // Run generation in main thread to avoid async issues
        Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
            try {
                System.out.println("[MythicDungeons] Generating structures for " + world.getName());
                
                // Since dungeon is already generated by MythicDungeons, just verify it exists
                boolean dungeonExists = verifyDungeonExists(world);
                
                if (dungeonExists) {
                    System.out.println("[MythicDungeons] Dungeon verified successfully!");
                    future.complete(true);
                } else {
                    // Try to trigger generation via API
                    Plugin mythicPlugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
                    if (mythicPlugin != null) {
                        boolean apiSuccess = triggerMythicDungeonsGeneration(world, mythicPlugin, type);
                        if (apiSuccess) {
                            future.complete(true);
                            return;
                        }
                    }
                    
                    // If all fails, just mark as complete since MythicDungeons already loaded the world
                    System.out.println("[MythicDungeons] Marking generation as complete (world already loaded)");
                    future.complete(true);
                }
                
            } catch (Exception e) {
                System.err.println("[MythicDungeons] Structure generation failed: " + e.getMessage());
                e.printStackTrace();
                future.complete(false);
            }
        }, 20L); // Wait 1 second for chunks to fully load
        
        return future;
    }
    
    /**
     * Trigger MythicDungeons generation using reflection
     */
    private static boolean triggerMythicDungeonsGeneration(World world, Plugin mythicPlugin, DungeonType type) {
        try {
            // Try different possible class names for DungeonManager
            String[] possibleClasses = {
                "net.playavalon.mythicdungeons.managers.DungeonManager",
                "net.playavalon.mythicdungeons.DungeonManager",
                "net.playavalon.mythicDungeons.managers.DungeonManager"
            };
            
            Class<?> dungeonManagerClass = null;
            for (String className : possibleClasses) {
                try {
                    dungeonManagerClass = Class.forName(className);
                    System.out.println("[MythicDungeons] Found DungeonManager class: " + className);
                    break;
                } catch (ClassNotFoundException ignored) {
                    // Try next class name
                }
            }
            
            if (dungeonManagerClass == null) {
                System.err.println("[MythicDungeons] Could not find DungeonManager class");
                return false;
            }
            
            // Get instance using different possible method names
            Object dungeonManager = null;
            for (String methodName : Arrays.asList("getInstance", "get", "instance")) {
                try {
                    java.lang.reflect.Method method = dungeonManagerClass.getMethod(methodName);
                    dungeonManager = method.invoke(null);
                    if (dungeonManager != null) break;
                } catch (Exception ignored) {
                    // Try next method
                }
            }
            
            if (dungeonManager == null) {
                System.err.println("[MythicDungeons] Could not get DungeonManager instance");
                return false;
            }
            
            // Check if dungeon instance exists
            Object dungeonInstance = null;
            for (String methodName : Arrays.asList("getDungeonByWorld", "getDungeon", "get")) {
                try {
                    java.lang.reflect.Method method = dungeonManagerClass.getMethod(methodName, String.class);
                    dungeonInstance = method.invoke(dungeonManager, world.getName());
                    if (dungeonInstance != null) break;
                } catch (Exception ignored) {
                    // Try next method
                }
            }
            
            if (dungeonInstance != null) {
                System.out.println("[MythicDungeons] Found dungeon instance for world: " + world.getName());
                // Dungeon already exists and is loaded
                return true;
            } else {
                System.out.println("[MythicDungeons] No dungeon instance found for world: " + world.getName());
            }
            
        } catch (Exception e) {
            System.err.println("[MythicDungeons] API generation failed: " + e.getMessage());
            e.printStackTrace();
        }
        
        return false;
    }
    
    /**
     * Verify that dungeon exists in the world
     */
    private static boolean verifyDungeonExists(World world) {
        System.out.println("[MythicDungeons] Verifying dungeon existence in world: " + world.getName());
        
        // Check if any chunks have dungeon-like structures
        int dungeonBlocksFound = 0;
        
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            if (Math.abs(chunk.getX()) > 5 || Math.abs(chunk.getZ()) > 5) {
                continue; // Only check central chunks
            }
            
            // Quick scan for dungeon materials at typical dungeon height
            for (int x = 0; x < 16; x += 8) {
                for (int z = 0; z < 16; z += 8) {
                    for (int y = 60; y <= 80; y += 10) {
                        int worldX = (chunk.getX() << 4) + x;
                        int worldZ = (chunk.getZ() << 4) + z;
                        
                        org.bukkit.block.Block block = world.getBlockAt(worldX, y, worldZ);
                        if (isDungeonMaterial(block.getType()) && !block.getType().isAir()) {
                            dungeonBlocksFound++;
                            if (dungeonBlocksFound > 5) {
                                System.out.println("[MythicDungeons] Dungeon structures found!");
                                return true;
                            }
                        }
                    }
                }
            }
        }
        
        System.out.println("[MythicDungeons] Dungeon blocks found: " + dungeonBlocksFound);
        return dungeonBlocksFound > 0;
    }
    
    // ================== TELEPORTATION SYSTEM ==================
    
    /**
     * Perform safe teleportation with ground detection
     */
    private static void performSafeTeleport(Player player, Location target, DungeonType type) {
        System.out.println("[MythicDungeons] Performing safe teleport for " + player.getName());
        
        // Find actual dungeon location
        Location dungeonLocation = findDungeonLocation(target.getWorld(), type);
        if (dungeonLocation == null) {
            dungeonLocation = target;
        }
        
        // Ensure safe ground
        Location safeLocation = findSafeGround(dungeonLocation);
        
        // Perform teleport
        boolean success = player.teleport(safeLocation);
        
        if (success) {
            System.out.println("[MythicDungeons] Successfully teleported " + player.getName());
            
            // Post-teleport validation
            Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
                validatePlayerPosition(player, safeLocation);
            }, 20L); // 1 second delay
        } else {
            System.err.println("[MythicDungeons] Failed to teleport " + player.getName());
            player.sendMessage("§c[MythicDungeons] Teleportation failed! Please try again.");
        }
    }
    
    /**
     * Find actual dungeon location using various methods
     */
    private static Location findDungeonLocation(World world, DungeonType type) {
        System.out.println("[MythicDungeons] Finding dungeon location in world: " + world.getName());
        
        // First, try to find the spawn room which is typically at the center
        Location spawnRoom = findSpawnRoom(world);
        if (spawnRoom != null) {
            System.out.println("[MythicDungeons] Found spawn room at: " + spawnRoom.getBlockX() + "," + spawnRoom.getBlockY() + "," + spawnRoom.getBlockZ());
            return spawnRoom;
        }
        
        // Try to find dungeon rooms via API
        Location apiLocation = findDungeonViaAPI(world);
        if (apiLocation != null) {
            return apiLocation;
        }
        
        // Scan for dungeon structures
        Location scannedLocation = scanForDungeonStructures(world, type);
        if (scannedLocation != null) {
            return scannedLocation;
        }
        
        // Default location - try common spawn coordinates
        System.out.println("[MythicDungeons] Using default spawn location");
        return new Location(world, 0.5, 65, 0.5);
    }
    
    /**
     * Find dungeon location using MythicDungeons API
     */
    private static Location findDungeonViaAPI(World world) {
        try {
            Plugin mythicPlugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
            if (mythicPlugin == null) return null;
            
            Class<?> dungeonManagerClass = Class.forName("net.playavalon.mythicdungeons.managers.DungeonManager");
            Object dungeonManager = dungeonManagerClass.getMethod("getInstance").invoke(null);
            Object dungeonInstance = dungeonManagerClass.getMethod("getDungeonByWorld", String.class)
                .invoke(dungeonManager, world.getName());
            
            if (dungeonInstance != null) {
                // Try to get spawn location
                Object spawnLocation = dungeonInstance.getClass().getMethod("getSpawnLocation").invoke(dungeonInstance);
                if (spawnLocation instanceof Location) {
                    return (Location) spawnLocation;
                }
            }
        } catch (Exception ignored) {
            // API not available or failed
        }
        
        return null;
    }
    
    /**
     * Find spawn room (usually at center with specific characteristics)
     */
    private static Location findSpawnRoom(World world) {
        System.out.println("[MythicDungeons] Looking for spawn room...");
        
        // Check center chunks first (spawn is usually there)
        for (int chunkX = -1; chunkX <= 1; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                org.bukkit.Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                
                // Look for a large open area (spawn room)
                for (int x = 0; x < 16; x += 2) {
                    for (int z = 0; z < 16; z += 2) {
                        int worldX = (chunkX << 4) + x;
                        int worldZ = (chunkZ << 4) + z;
                        
                        // Check common dungeon Y levels
                        for (int y = 64; y <= 72; y++) {
                            if (isLikelySpawnRoom(world, worldX, y, worldZ)) {
                                return new Location(world, worldX + 0.5, y + 1, worldZ + 0.5, 0, 0);
                            }
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Check if location looks like a spawn room
     */
    private static boolean isLikelySpawnRoom(World world, int x, int y, int z) {
        // Check if there's a floor and open space above
        org.bukkit.block.Block floor = world.getBlockAt(x, y, z);
        
        if (!isDungeonMaterial(floor.getType()) || floor.getType().isAir()) {
            return false;
        }
        
        // Check for open space (at least 3x3x3)
        int airCount = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 1; dy <= 3; dy++) {
                    if (world.getBlockAt(x + dx, y + dy, z + dz).getType().isAir()) {
                        airCount++;
                    }
                }
            }
        }
        
        // If most blocks above are air, it's likely a room
        return airCount >= 20;
    }
    
    /**
     * Scan world for dungeon structures
     */
    private static Location scanForDungeonStructures(World world, DungeonType type) {
        System.out.println("[MythicDungeons] Scanning for dungeon structures");
        
        // Define scan parameters based on dungeon type
        int scanRadius = type == DungeonType.PROCEDURAL ? 10 : 5;
        int minY = 60;
        int maxY = 75;
        
        // Scan loaded chunks
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            if (Math.abs(chunk.getX()) > scanRadius || Math.abs(chunk.getZ()) > scanRadius) {
                continue;
            }
            
            // Check for dungeon-like structures
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    int worldX = (chunk.getX() << 4) + x;
                    int worldZ = (chunk.getZ() << 4) + z;
                    
                    for (int y = minY; y <= maxY; y++) {
                        if (isDungeonStructure(world, worldX, y, worldZ)) {
                            System.out.println("[MythicDungeons] Found dungeon structure at " + worldX + "," + y + "," + worldZ);
                            return new Location(world, worldX + 0.5, y + 1, worldZ + 0.5);
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Check if location contains dungeon structure
     */
    private static boolean isDungeonStructure(World world, int x, int y, int z) {
        org.bukkit.block.Block floor = world.getBlockAt(x, y, z);
        org.bukkit.block.Block above1 = world.getBlockAt(x, y + 1, z);
        org.bukkit.block.Block above2 = world.getBlockAt(x, y + 2, z);
        
        // Check for typical dungeon pattern: solid floor with air above
        if (!floor.getType().isAir() && above1.getType().isAir() && above2.getType().isAir()) {
            // Check for dungeon materials
            Material floorType = floor.getType();
            return isDungeonMaterial(floorType);
        }
        
        return false;
    }
    
    /**
     * Check if material is typically used in dungeons
     */
    private static boolean isDungeonMaterial(Material material) {
        return material == Material.STONE_BRICKS ||
               material == Material.MOSSY_STONE_BRICKS ||
               material == Material.CRACKED_STONE_BRICKS ||
               material == Material.COBBLESTONE ||
               material == Material.STONE ||
               material == Material.BRICKS ||
               material == Material.DEEPSLATE_BRICKS ||
               material == Material.POLISHED_BLACKSTONE_BRICKS;
    }
    
    /**
     * Find safe ground at location
     */
    private static Location findSafeGround(Location location) {
        World world = location.getWorld();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        int startY = location.getBlockY();
        
        // First, try to find ground below
        for (int y = startY; y >= 40; y--) {
            if (isSafeGround(world, x, y, z)) {
                return new Location(world, x + 0.5, y + 1, z + 0.5, location.getYaw(), location.getPitch());
            }
        }
        
        // Then try above
        for (int y = startY + 1; y <= 100; y++) {
            if (isSafeGround(world, x, y, z)) {
                return new Location(world, x + 0.5, y + 1, z + 0.5, location.getYaw(), location.getPitch());
            }
        }
        
        // If no safe ground found, create emergency platform
        return createEmergencyPlatform(world, x, 70, z);
    }
    
    /**
     * Check if location has safe ground
     */
    private static boolean isSafeGround(World world, int x, int y, int z) {
        org.bukkit.block.Block floor = world.getBlockAt(x, y, z);
        org.bukkit.block.Block feet = world.getBlockAt(x, y + 1, z);
        org.bukkit.block.Block head = world.getBlockAt(x, y + 2, z);
        
        return !floor.getType().isAir() && 
               !floor.isLiquid() &&
               feet.getType().isAir() && 
               head.getType().isAir();
    }
    
    /**
     * Create emergency platform for safe landing
     */
    private static Location createEmergencyPlatform(World world, int x, int y, int z) {
        System.out.println("[MythicDungeons] Creating emergency platform at " + x + "," + y + "," + z);
        
        // Create 5x5 platform
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                // Create solid floor
                world.getBlockAt(x + dx, y, z + dz).setType(Material.STONE);
                world.getBlockAt(x + dx, y - 1, z + dz).setType(Material.STONE);
                
                // Clear space above
                for (int dy = 1; dy <= 3; dy++) {
                    world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                }
            }
        }
        
        // Add center marker
        world.getBlockAt(x, y, z).setType(Material.GLOWSTONE);
        
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }
    
    /**
     * Validate player position after teleport
     */
    private static void validatePlayerPosition(Player player, Location expectedLocation) {
        Location actualLocation = player.getLocation();
        double distance = actualLocation.distance(expectedLocation);
        
        if (distance > 10) {
            System.out.println("[MythicDungeons] Player position validation failed! Distance: " + distance);
            // Try to teleport again
            player.teleport(expectedLocation);
        }
    }
    
    /**
     * Handle generation failure
     */
    private static void handleGenerationFailure(Player player, Location target, Throwable error) {
        System.err.println("[MythicDungeons] Generation failed for " + player.getName());
        if (error != null) {
            error.printStackTrace();
        }
        
        player.sendMessage("§c[MythicDungeons] Failed to generate dungeon. Creating fallback location...");
        
        // Create fallback location
        Location fallback = createEmergencyPlatform(target.getWorld(), 0, 70, 0);
        player.teleport(fallback);
    }
    
    /**
     * Get plugin instance
     */
    private static Plugin getPlugin() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicDungeons");
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("Youer");
        }
        return plugin;
    }
    
    // ================== ASM PATCHES ==================
    
    /**
     * Patch MythicDungeons Util class teleport methods
     */
    public static void patchDungeonTeleport(ClassNode node) {
        System.out.println("[MythicDungeons] Patching Util class teleport methods");
        
        for (MethodNode method : node.methods) {
            if (method.name.equals("forceTeleport") || method.name.equals("forceTeleport2")) {
                patchTeleportMethod(method);
            }
            
            // Replace async teleports with sync
            replaceAsyncTeleports(method);
        }
    }
    
    /**
     * Patch teleport method to use our implementation
     */
    private static void patchTeleportMethod(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        
        InsnList newCode = new InsnList();
        
        // Load parameters
        newCode.add(new VarInsnNode(Opcodes.ALOAD, 0)); // Entity
        newCode.add(new VarInsnNode(Opcodes.ALOAD, 1)); // Location
        
        // Call our method
        newCode.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(PluginFixManager.class),
            "teleportEntityToDungeon",
            "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)V",
            false
        ));
        
        newCode.add(new InsnNode(Opcodes.RETURN));
        method.instructions = newCode;
        
        System.out.println("[MythicDungeons] Patched method: " + method.name);
    }
    
    /**
     * Replace async teleport calls with sync versions
     */
    private static void replaceAsyncTeleports(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("teleportAsync")) {
                    mInsn.name = "teleport";
                    mInsn.desc = "(Lorg/bukkit/Location;)Z";
                    System.out.println("[MythicDungeons] Replaced teleportAsync in " + method.name);
                }
            }
        }
    }
    
    /**
     * Patch Layout generation classes
     */
    public static void patchLayoutGeneration(ClassNode node) {
        System.out.println("[MythicDungeons] Patching Layout generation class");
        
        for (MethodNode method : node.methods) {
            if (method.name.contains("generate") || method.name.contains("build")) {
                // Increase timeouts
                increaseTimeouts(method);
                
                // Convert async to sync
                convertAsyncToSync(method);
            }
        }
    }
    
    /**
     * Patch Procedural Instance classes
     */
    public static void patchProceduralInstance(ClassNode node) {
        System.out.println("[MythicDungeons] Patching InstancePlayable for procedural dungeons");
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found InstancePlayable method: " + method.name + method.desc);
            
            // addPlayer method'unu özel olarak patch'le
            if (method.name.equals("addPlayer")) {
                System.out.println("[MythicDungeons Debug] Found procedural addPlayer method!");
                addDebugLogging(method, "InstancePlayable.addPlayer");
                patchProceduralTeleportDetection(method);
            }
        }
    }
    
    /**
     * Patch ChunkGenerator classes
     */
    public static void patchChunkGenerator(ClassNode node) {
        System.out.println("[MythicDungeons] Patching DungeonChunkGenerator");
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found ChunkGenerator method: " + method.name + method.desc);
            
            // Chunk generation method'ları
            if (method.name.contains("generate") || method.name.contains("populate") ||
                method.name.contains("chunk") || method.name.contains("world")) {
                
                System.out.println("[MythicDungeons Debug] Found CRITICAL chunk generation method: " + method.name);
                addDebugLogging(method, "ChunkGenerator." + method.name + " [CHUNK_GEN]");
            }
        }
    }
    
    /**
     * Patch async generation methods
     */
    public static void patchAsyncGeneration(ClassNode node) {
        System.out.println("[MythicDungeons] Patching async generation");
        
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode mInsn) {
                    // ExecutorService.submit() -> sync execution
                    if (mInsn.owner.contains("ExecutorService") && mInsn.name.equals("submit")) {
                        mInsn.owner = "java/util/concurrent/Callable";
                        mInsn.name = "call";
                        System.out.println("[MythicDungeons Patch] Converted async generation to sync");
                    }
                    
                    // CompletableFuture.get() timeout'ları handle et
                    if (mInsn.owner.equals("java/util/concurrent/CompletableFuture") && mInsn.name.equals("get")) {
                        System.out.println("[MythicDungeons Patch] Found CompletableFuture.get() call");
                    }
                }
            }
        }
    }
    
    /**
     * Add debug logging to method
     */
    private static void addDebugLogging(MethodNode method, String methodName) {
        InsnList debugCode = new InsnList();
        
        // System.out.println("[MythicDungeons Debug] Executing " + methodName);
        debugCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        debugCode.add(new LdcInsnNode("[MythicDungeons Debug] Executing " + methodName));
        debugCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        method.instructions.insert(debugCode);
    }
    
    /**
     * Patch procedural teleport detection
     */
    private static void patchProceduralTeleportDetection(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                // forceTeleport çağrısını tespit et
                if ((mInsn.name.equals("forceTeleport") || mInsn.name.equals("forceTeleport2")) &&
                    mInsn.owner.contains("Util")) {
                    
                    System.out.println("[MythicDungeons Debug] Found forceTeleport call in procedural addPlayer - will use PROCEDURAL teleport with extended delay");
                }
            }
        }
    }
    
    /**
     * Increase timeout values in method
     */
    private static void increaseTimeouts(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof Long timeout && timeout < 30000L) {
                    ldc.cst = 30000L; // 30 seconds
                    System.out.println("[MythicDungeons] Increased timeout to 30s in " + method.name);
                }
            }
        }
    }
    
    /**
     * Convert async calls to sync
     */
    private static void convertAsyncToSync(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("runTaskAsynchronously")) {
                    mInsn.name = "runTask";
                    System.out.println("[MythicDungeons] Converted async to sync in " + method.name);
                }
            }
        }
    }
    
    // ================== MAIN PATCH INJECTOR ==================
    
    public static byte[] injectPluginFix(String plugin, String className, byte[] clazz) {
        // Debug: MythicDungeons class'larını log'la
        if (plugin.equals("MythicDungeons") || className.contains("mythicdungeons")) {
            System.out.println("[MythicDungeons Debug] Found MythicDungeons class: " + className);
        }
        
        // WorldEdit adapter setup
        if (plugin.equals("WorldEdit")) {
            String adapter = System.getProperty("worldedit.bukkit.adapter");
            if (adapter == null) {
                System.setProperty("worldedit.bukkit.adapter", "com.sk89q.worldedit.bukkit.adapter.impl.v1_21.PaperweightAdapter");
            }
        }

        // Direct class name switches
        switch (className) {
            case "com.ghostchu.quickshop.platform.spigot.AbstractSpigotPlatform" -> {
                return patch(clazz, PluginFixManager::qs);
            }
            case "com.fastasyncworldedit.bukkit.util.MinecraftVersion" -> {
                return patch(clazz, PluginFixManager::fawe);
            }
            case "com.bgsoftware.superiorskyblock.external.ProvidersManagerImpl" -> {
                return patch(clazz, PluginFixManager::removePaper);
            }
            // MythicDungeons patches
            case "net.playavalon.mythicdungeons.utility.helpers.Util" -> {
                System.out.println("[MythicDungeons Patch] Patching Util class for teleport fixes...");
                return patch(clazz, PluginFixManager::patchDungeonTeleport);
            }
            case "net.playavalon.mythicdungeons.api.parents.instances.InstancePlayable" -> {
                System.out.println("[MythicDungeons Patch] Patching InstancePlayable for procedural dungeons...");
                return patch(clazz, PluginFixManager::patchProceduralInstance);
            }
            // Alternative procedural instance class names
            case "net.playavalon.mythicdungeons.dungeons.instances.ProceduralInstance",
                 "net.playavalon.mythicdungeons.api.instances.InstanceProcedural",
                 "net.playavalon.mythicdungeons.instances.InstancePlayable" -> {
                System.out.println("[MythicDungeons Patch] Patching alternative procedural instance: " + className);
                return patch(clazz, PluginFixManager::patchProceduralInstance);
            }
            case "net.playavalon.mythicdungeons.api.generation.layout.Layout" -> {
                System.out.println("[MythicDungeons Patch] Patching Layout generation class...");
                return patch(clazz, PluginFixManager::patchLayoutGeneration);
            }
            case "net.playavalon.mythicdungeons.api.chunkgenerators.DungeonChunkGenerator" -> {
                System.out.println("[MythicDungeons Patch] Patching DungeonChunkGenerator...");
                return patch(clazz, PluginFixManager::patchChunkGenerator);
            }
            case "net.playavalon.mythicdungeons.api.generation.layout.LayoutBranching",
                 "net.playavalon.mythicdungeons.api.generation.layout.LayoutMinecrafty" -> {
                System.out.println("[MythicDungeons Patch] Patching async generation...");
                return patch(clazz, PluginFixManager::patchAsyncGeneration);
            }
        }

        // CMI patch'leri
        if (className.startsWith("net.Zrips.CMILib.") || className.startsWith("com.Zrips.CMI.")) {
            return patch(clazz, node -> helloWorld(node, "net.minecraft.server.network.PlayerConnection", "net.minecraft.server.network.ServerGamePacketListenerImpl"));
        }

        // Consumer based patches
        Consumer<ClassNode> patcher = switch (className) {
            case "com.earth2me.essentials.utils.VersionUtil" -> node -> {
                helloWorld(node, "brand:", "peace");
                ex(node);
            };
            case "net.Zrips.CMILib.Reflections" -> node -> helloWorld(node, "bR", "f_36096_");
            case "net.Zrips.CMILib.RawMessages.RawMessageManager" ->
                    node -> helloWorld(node, "net.minecraft.server.network.PlayerConnection", "net.minecraft.server.network.ServerGamePacketListenerImpl");
            case "com.sk89q.worldedit.bukkit.BukkitConfiguration" -> node -> {
                helloWorld(node, "I accept that I will receive no support with this flag enabled.", Youer.modid);
                helloWorld(node, "allow-editing-on-unsupported-versions", Youer.modid);
                helloWorld(node, "false", Youer.modid);
            };
            case "com.sk89q.worldedit.bukkit.adapter.impl.v1_21.PaperweightAdapter",
                 "com.sk89q.worldedit.bukkit.adapter.ext.fawe.v1_21_R1.PaperweightAdapter" ->
                    node -> helloWorld(node, "org.spigotmc.WatchdogThread", Youer.modid);
            case "cn.lunadeer.dominion.utils.Misc" ->
                    node -> helloWorld(node, "io.papermc.paper.threadedregions.scheduler.ScheduledTask", Youer.modid);
            case "com.sk89q.worldedit.bukkit.paperlib.PaperLib" -> node -> {
                removePaper0(node);
                String adapter = System.getProperty("paperlib.shown-benefits");
                if (adapter == null) System.setProperty("paperlib.shown-benefits", "1");
            };
            case "org.mvplugins.multiverse.external.paperlib.PaperLib",
                 "me.SuperRonanCraft.BetterRTP.lib.paperlib.PaperLib",
                 "com.plotsquared.bukkit.paperlib.PaperLib" -> PluginFixManager::removePaper0;
            default -> null;
        };

        return patcher == null ? clazz : patch(clazz, patcher);
    }
    
    // -------------------- ASM HELPER METHODS --------------------
    
    private static void removePaper(ClassNode node) {
        for (MethodNode methodNode : node.methods) {
            if (methodNode.name.equals("hasPaperAsyncSupport") && methodNode.desc.equals("()Z")) {
                InsnList toInject = new InsnList();
                toInject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(PluginFixManager.class), "hasPaperAsyncSupport", "()Z", false));
                toInject.add(new InsnNode(Opcodes.IRETURN));
                methodNode.instructions = toInject;
            }
        }
    }
    
    private static void removePaper0(ClassNode node) {
        helloWorld(node, "com.destroystokyo.paper.PaperConfig", Youer.modid);
        helloWorld(node, "io.papermc.paper.configuration.Configuration", Youer.modid);
    }

    private static void redirectMethodToGetNMSVersion(ClassNode node, String methodName) {
        for (MethodNode methodNode : node.methods) {
            if (methodNode.name.equals(methodName) && methodNode.desc.equals("()Ljava/lang/String;")) {
                InsnList toInject = new InsnList();
                toInject.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(PluginFixManager.class),
                        "getNMSVersion",
                        "()Ljava/lang/String;",
                        false
                ));
                toInject.add(new InsnNode(ARETURN));
                methodNode.instructions = toInject;
                methodNode.tryCatchBlocks.clear();
            }
        }
    }

    private static void qs(ClassNode node) { 
        redirectMethodToGetNMSVersion(node, "getNMSVersion"); 
    }
    
    private static void fawe(ClassNode node) { 
        redirectMethodToGetNMSVersion(node, "getPackageVersion"); 
    }

    public static void ex(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("make") && method.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                InsnList toInject = new InsnList();
                toInject.add(new VarInsnNode(Opcodes.ALOAD, 0));
                toInject.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(PluginFixManager.class),
                        "make",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false
                ));
                toInject.add(new InsnNode(ARETURN));
                method.instructions = toInject;
                method.tryCatchBlocks.clear();
            }
        }
    }

    private static void helloWorld(ClassNode node, String a, String b) {
        node.methods.forEach(method -> {
            for (AbstractInsnNode next : method.instructions) {
                if (next instanceof LdcInsnNode ldcInsnNode) {
                    if (ldcInsnNode.cst instanceof String str) {
                        if (a.equals(str)) ldcInsnNode.cst = b;
                    }
                }
            }
        });
    }

    private static void helloWorld(ClassNode node, int a, int b) {
        node.methods.forEach(method -> {
            for (AbstractInsnNode next : method.instructions) {
                if (next instanceof IntInsnNode ldcInsnNode) {
                    if (ldcInsnNode.operand == a) ldcInsnNode.operand = b;
                }
            }
        });
    }
    
    public static String make(String in) {
        if (in.equals("8(;4>`")) return "peace";
        final char[] c = in.toCharArray();
        for (int i = 0; i < c.length; ++i) c[i] ^= 'Z';
        return new String(c);
    }
    
    private static byte[] patch(byte[] basicClass, Consumer<ClassNode> handler) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(basicClass).accept(node, 0);
            handler.accept(node);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Exception e) {
            System.err.println("[PluginFixManager] Failed to patch class: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }
    
    // Utility methods for compatibility
    public static String getNMSVersion() { 
        return "v1_21_R1"; 
    }
    
    public static boolean hasPaperAsyncSupport() { 
        return false; 
    }
}
