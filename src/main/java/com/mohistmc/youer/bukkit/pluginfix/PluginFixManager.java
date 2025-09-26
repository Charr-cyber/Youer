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
 * Simplified PluginFixManager - Removed force teleport and dungeon scanning
 * @version 4.0 - Simplified version without force teleport and dungeon scanning
 */
public class PluginFixManager {
    
    // ================== SIMPLIFIED TELEPORT HANDLER ==================
    
    /**
     * Simple teleport wrapper that preserves coordinates and waits for dungeon generation
     */
    public static CompletableFuture<Boolean> safeTeleportWithChunkLoad(Entity entity, Location target) {
        System.out.println("[MythicDungeons] safeTeleportWithChunkLoad called for " + entity.getName() + 
                          " to " + target.getWorld().getName() + " X:" + target.getBlockX() + 
                          " Y:" + target.getBlockY() + " Z:" + target.getBlockZ());
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (entity == null || target == null) {
            future.complete(false);
            return future;
        }
        
        // Ensure we're on the main thread for chunk loading
        if (!Bukkit.isPrimaryThread()) {
            // Schedule on main thread
            Bukkit.getScheduler().runTask(getPlugin(), () -> {
                performSimpleTeleport(entity, target, future);
            });
        } else {
            // Already on main thread
            performSimpleTeleport(entity, target, future);
        }
        
        return future;
    }
    
    /**
     * Perform simple teleportation with safe ground detection
     */
    private static void performSimpleTeleport(Entity entity, Location target, CompletableFuture<Boolean> future) {
        World world = target.getWorld();
        
        // Preload chunks around target
        int chunkX = target.getBlockX() >> 4;
        int chunkZ = target.getBlockZ() >> 4;
        
        System.out.println("[MythicDungeons] Loading chunks around chunk (" + chunkX + "," + chunkZ + ")");
        
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (!world.isChunkLoaded(chunkX + x, chunkZ + z)) {
                    world.loadChunk(chunkX + x, chunkZ + z);
                }
            }
        }
        
        System.out.println("[MythicDungeons] Chunks loaded, performing teleport...");
        
        // Wait for dungeon generation to complete, then teleport
        Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
            // Find safe ground at the target location
            Location safeLocation = findSafeGroundAtTarget(target);
            
            boolean success = entity.teleport(safeLocation);
            
            if (success) {
                System.out.println("[MythicDungeons] Successfully teleported " + entity.getName() + 
                    " to X:" + safeLocation.getBlockX() + " Y:" + safeLocation.getBlockY() + " Z:" + safeLocation.getBlockZ());
            } else {
                System.err.println("[MythicDungeons] Failed to teleport " + entity.getName());
            }
            
            future.complete(success);
        }, 20L); // 1 second delay to allow dungeon generation
    }
    
    /**
     * Find safe ground at the target location using MythicDungeons DungeonChunkGenerator
     */
    private static Location findSafeGroundAtTarget(Location target) {
        World world = target.getWorld();
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int startY = target.getBlockY();
        
        System.out.println("[MythicDungeons] Finding safe ground at target X:" + x + " Z:" + z + " starting from Y:" + startY);
        
        // First try to get actual dungeon room from ChunkGenerator
        Location dungeonRoom = getDungeonRoomFromGenerator(world, x, z);
        if (dungeonRoom != null) {
            System.out.println("[MythicDungeons] Found dungeon room from ChunkGenerator at Y:" + dungeonRoom.getBlockY());
            return dungeonRoom;
        }
        
        // First check if the original target is safe
        if (isSafeGround(world, x, startY - 1, z)) {
            System.out.println("[MythicDungeons] Original target is safe!");
            return new Location(world, x + 0.5, startY, z + 0.5, target.getYaw(), target.getPitch());
        }
        
        // Scan downward from target Y to find dungeon floor
        for (int y = startY - 1; y >= 5; y--) {
            if (isSafeGround(world, x, y, z)) {
                System.out.println("[MythicDungeons] Found safe ground below at Y:" + (y + 1));
                return new Location(world, x + 0.5, y + 1, z + 0.5, target.getYaw(), target.getPitch());
            }
        }
        
        // If no safe ground found directly below, scan in a small radius
        System.out.println("[MythicDungeons] No safe ground directly below, scanning nearby...");
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = Math.max(5, startY - 50); y <= startY + 10; y++) {
                    if (isSafeGround(world, x + dx, y, z + dz)) {
                        System.out.println("[MythicDungeons] Found safe ground nearby at X:" + (x + dx) + " Y:" + (y + 1) + " Z:" + (z + dz));
                        return new Location(world, x + dx + 0.5, y + 1, z + dz + 0.5, target.getYaw(), target.getPitch());
                    }
                }
            }
        }
        
        // Last resort: create a platform at a reasonable height
        System.out.println("[MythicDungeons] No safe ground found, creating platform at Y:70");
        return createEmergencyPlatform(world, x, 70, z);
    }
    
    /**
     * Get actual dungeon room location from MythicDungeons DungeonChunkGenerator
     */
    private static Location getDungeonRoomFromGenerator(World world, int targetX, int targetZ) {
        try {
            org.bukkit.generator.ChunkGenerator gen = world.getGenerator();
            if (gen != null && gen.getClass().getName().contains("DungeonChunkGenerator")) {
                System.out.println("[MythicDungeons] Found DungeonChunkGenerator, getting room bounds...");
                
                // Get room bounds from ChunkGenerator
                java.lang.reflect.Method getRoomBounds = gen.getClass().getMethod("getRoomBounds");
                Object roomBoundsObj = getRoomBounds.invoke(gen);
                
                if (roomBoundsObj instanceof Map) {
                    Map<?, ?> roomBounds = (Map<?, ?>) roomBoundsObj;
                    System.out.println("[MythicDungeons] Found " + roomBounds.size() + " rooms in ChunkGenerator");
                    
                    // Find the closest room to target coordinates
                    Object closestRoomKey = null;
                    int bestDistance = Integer.MAX_VALUE;
                    int targetChunkX = targetX >> 4;
                    int targetChunkZ = targetZ >> 4;
                    
                    for (Object key : roomBounds.keySet()) {
                        try {
                            int chunkX, chunkZ;
                            if (key.getClass().getName().contains("Vector2i")) {
                                chunkX = (int) key.getClass().getMethod("x").invoke(key);
                                chunkZ = (int) key.getClass().getMethod("y").invoke(key);
                            } else {
                                // Try field access as fallback
                                chunkX = (int) key.getClass().getField("x").get(key);
                                chunkZ = (int) key.getClass().getField("y").get(key);
                            }
                            
                            int distance = Math.abs(chunkX - targetChunkX) + Math.abs(chunkZ - targetChunkZ);
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                closestRoomKey = key;
                            }
                            
                            System.out.println("[MythicDungeons] Room at chunk (" + chunkX + "," + chunkZ + ") distance: " + distance);
                        } catch (Exception e) {
                            System.out.println("[MythicDungeons] Could not extract chunk coordinates from room key: " + e.getMessage());
                        }
                    }
                    
                    if (closestRoomKey != null) {
                        int chunkX, chunkZ;
                        try {
                            if (closestRoomKey.getClass().getName().contains("Vector2i")) {
                                chunkX = (int) closestRoomKey.getClass().getMethod("x").invoke(closestRoomKey);
                                chunkZ = (int) closestRoomKey.getClass().getMethod("y").invoke(closestRoomKey);
                            } else {
                                chunkX = (int) closestRoomKey.getClass().getField("x").get(closestRoomKey);
                                chunkZ = (int) closestRoomKey.getClass().getField("y").get(closestRoomKey);
                            }
                            
                            // Convert chunk coordinates to world coordinates (center of chunk)
                            int worldX = (chunkX << 4) + 8;
                            int worldZ = (chunkZ << 4) + 8;
                            
                            System.out.println("[MythicDungeons] Using closest room at chunk (" + chunkX + "," + chunkZ + ") world (" + worldX + "," + worldZ + ")");
                            
                            // Find the Y level of the room floor
                            for (int y = 5; y <= 250; y++) {
                                org.bukkit.block.Block floor = world.getBlockAt(worldX, y, worldZ);
                                org.bukkit.block.Block above1 = world.getBlockAt(worldX, y + 1, worldZ);
                                org.bukkit.block.Block above2 = world.getBlockAt(worldX, y + 2, worldZ);
                                
                                // Look for solid floor with air above (typical room pattern)
                                if (!floor.getType().isAir() && 
                                    above1.getType().isAir() && 
                                    above2.getType().isAir()) {
                                    
                                    System.out.println("[MythicDungeons] Found dungeon room floor at Y=" + y);
                                    return new Location(world, worldX + 0.5, y + 1, worldZ + 0.5);
                                }
                            }
                            
                            // If no floor found, try scanning a bit around the center
                            for (int dx = -2; dx <= 2; dx++) {
                                for (int dz = -2; dz <= 2; dz++) {
                                    for (int y = 5; y <= 250; y++) {
                                        org.bukkit.block.Block floor = world.getBlockAt(worldX + dx, y, worldZ + dz);
                                        org.bukkit.block.Block above1 = world.getBlockAt(worldX + dx, y + 1, worldZ + dz);
                                        org.bukkit.block.Block above2 = world.getBlockAt(worldX + dx, y + 2, worldZ + dz);
                                        
                                        if (!floor.getType().isAir() && 
                                            above1.getType().isAir() && 
                                            above2.getType().isAir()) {
                                            
                                            System.out.println("[MythicDungeons] Found dungeon room floor at Y=" + y + " with offset (" + dx + "," + dz + ")");
                                            return new Location(world, worldX + dx + 0.5, y + 1, worldZ + dz + 0.5);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("[MythicDungeons] Could not extract coordinates from closest room: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[MythicDungeons] Could not get room from ChunkGenerator: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Check if location has safe ground
     */
    private static boolean isSafeGround(World world, int x, int y, int z) {
        if (y < 0 || y >= 256) return false;
        
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
        
        // Create 3x3 platform
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // Create solid floor
                world.getBlockAt(x + dx, y, z + dz).setType(Material.STONE);
                
                // Clear space above
                for (int dy = 1; dy <= 3; dy++) {
                    world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                }
            }
        }
        
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }
    
    /**
     * DEPRECATED - Kept for compatibility but not used
     */
    public static void teleportEntityToDungeon(Entity entity, Location target) {
        System.out.println("[MythicDungeons] WARNING: teleportEntityToDungeon called (deprecated)");
        if (entity != null && target != null) {
            safeTeleportWithChunkLoad(entity, target);
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

        // Use the main method
        teleportEntityToDungeon(entity, target);
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
    
    // ================== SIMPLIFIED ASM PATCHES ==================
    
    /**
     * Patch MythicDungeons Util class teleport methods - Simplified version
     */
    public static void patchDungeonTeleport(ClassNode node) {
        System.out.println("[MythicDungeons] Patching Util class teleport methods (simplified)");
        
        for (MethodNode method : node.methods) {
            if (method.name.equals("forceTeleport") || method.name.equals("forceTeleport2")) {
                patchTeleportMethod(method);
            }
            
            // Replace async teleports with sync
            replaceAsyncTeleports(method);
        }
    }
    
    /**
     * Patch teleport method - Simple replacement of teleportAsync calls
     */
    private static void patchTeleportMethod(MethodNode method) {
        System.out.println("[MythicDungeons] Patching forceTeleport method: " + method.name);
        
        // Add detailed logging at the beginning
        InsnList preCode = new InsnList();
        preCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        preCode.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        preCode.add(new InsnNode(Opcodes.DUP));
        preCode.add(new LdcInsnNode("[MythicDungeons] forceTeleport called: Entity="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        preCode.add(new LdcInsnNode(" Location="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        // Add detailed coordinate logging
        preCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        preCode.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        preCode.add(new InsnNode(Opcodes.DUP));
        preCode.add(new LdcInsnNode("[MythicDungeons] Target coordinates: X="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/bukkit/Location", "getX", "()D", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(D)Ljava/lang/StringBuilder;", false));
        preCode.add(new LdcInsnNode(" Y="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/bukkit/Location", "getY", "()D", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(D)Ljava/lang/StringBuilder;", false));
        preCode.add(new LdcInsnNode(" Z="));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        preCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/bukkit/Location", "getZ", "()D", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(D)Ljava/lang/StringBuilder;", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        preCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        // Insert logging at the beginning
        method.instructions.insert(preCode);
        
        // Find all teleportAsync calls and replace with our safe wrapper
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("teleportAsync") &&
                        "(Lorg/bukkit/Location;)Ljava/util/concurrent/CompletableFuture;".equals(mInsn.desc)) {
                    // Replace Player/Entity#teleportAsync(Location) with our safe static wrapper
                    mInsn.owner = Type.getInternalName(PluginFixManager.class);
                    mInsn.name = "safeTeleportWithChunkLoad";
                    mInsn.desc = "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)Ljava/util/concurrent/CompletableFuture;";
                    mInsn.itf = false;
                    mInsn.setOpcode(Opcodes.INVOKESTATIC);
                    System.out.println("[MythicDungeons] Replaced teleportAsync(Location) with safeTeleportWithChunkLoad");
                }
            }
        }
        
        System.out.println("[MythicDungeons] Patched method: " + method.name);
    }
    
    /**
     * Replace async teleport calls with sync versions
     */
    private static void replaceAsyncTeleports(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("teleportAsync") && "(Lorg/bukkit/Location;)Ljava/util/concurrent/CompletableFuture;".equals(mInsn.desc)) {
                    // Simple overload: convert to sync teleport(Location)
                    mInsn.name = "teleport";
                    mInsn.desc = "(Lorg/bukkit/Location;)Z";
                    System.out.println("[MythicDungeons] Replaced teleportAsync(Location) in " + method.name);
                }
            }
        }
    }
    
    /**
     * Patch ChunkGenerator classes - Simplified version
     */
    public static void patchChunkGenerator(ClassNode node) {
        System.out.println("[MythicDungeons] Patching DungeonChunkGenerator (simplified)");
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found ChunkGenerator method: " + method.name + method.desc);
            
            // Add debug logging for chunk generation methods
            if (method.name.contains("generate") || method.name.contains("populate") ||
                method.name.contains("chunk") || method.name.contains("world")) {
                
                System.out.println("[MythicDungeons Debug] Found CRITICAL chunk generation method: " + method.name);
                addDebugLogging(method, "ChunkGenerator." + method.name + " [CHUNK_GEN]");
            }
        }
    }
    
    /**
     * Add debug logging to method
     */
    private static void addDebugLogging(MethodNode method, String methodName) {
        InsnList debugCode = new InsnList();
        
        debugCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        debugCode.add(new LdcInsnNode("[MythicDungeons Debug] Executing " + methodName));
        debugCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        method.instructions.insert(debugCode);
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
            // Simplified MythicDungeons patches - Only essential teleport fixes
            case "net.playavalon.mythicdungeons.utility.helpers.Util" -> {
                System.out.println("[MythicDungeons Patch] Patching Util class for teleport fixes (simplified)...");
                return patch(clazz, PluginFixManager::patchDungeonTeleport);
            }
            case "net.playavalon.mythicdungeons.api.chunkgenerators.DungeonChunkGenerator" -> {
                System.out.println("[MythicDungeons Patch] Patching DungeonChunkGenerator (simplified)...");
                return patch(clazz, PluginFixManager::patchChunkGenerator);
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
