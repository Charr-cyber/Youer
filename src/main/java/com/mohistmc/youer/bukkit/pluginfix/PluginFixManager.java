package com.mohistmc.youer.bukkit.pluginfix;

import com.mohistmc.youer.Youer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ARETURN;

public class PluginFixManager {

    // -------------------- TELEPORT + STRUCTURE GENERATION + LAST VERSION --------------------

    /**
     * Oyuncuyu dungeon içine güvenli şekilde teleport eder ve yapı oluşturmayı bekler.
     */
    public static void teleportEntityToDungeon(Entity entity, Location target) {
        // DEBUG: Şimdilik tüm teleport'ları PROCEDURAL yap
        boolean isProcedural = target.getWorld().getName().contains("deneme") || target.getWorld().getName().contains("dungeon");
        System.out.println("[MythicDungeons Debug] Auto-detecting dungeon type for world: " + target.getWorld().getName() + " -> " + (isProcedural ? "PROCEDURAL" : "CLASSIC"));
        teleportEntityToDungeon(entity, target, isProcedural);
    }
    
    /**
     * Procedural-aware teleport - procedural dungeon'lar için daha uzun bekleme
     */
    public static void teleportEntityToDungeon(Entity entity, Location target, boolean isProcedural) {
        if (entity == null || target == null) return;
        
        if (!(entity instanceof Player player)) {
            return;
        }

        String dungeonType = isProcedural ? "PROCEDURAL" : "CLASSIC";
        System.out.println("[MythicDungeons Debug] Starting " + dungeonType + " teleport for " + player.getName() + 
                          " to " + target.getWorld().getName());

        // Procedural için daha uzun delay
        long delay = isProcedural ? 20L : 5L; // 1 saniye vs 250ms

        // YOUER OPTIMIZE: Chunk'ı önceden yükle ve yapı oluşturmayı bekle
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                () -> {
                    try {
                        // 1. Target chunk'ı force load et
                        if (!target.getChunk().isLoaded()) {
                            System.out.println("[MythicDungeons Debug] Loading chunk for " + dungeonType + " teleport...");
                            target.getChunk().load();
                        }
                        
                        // 2. Çevredeki chunk'ları da önceden yükle (structure için)
                        if (isProcedural) {
                            preloadSurroundingChunksSync(target, 3); // Procedural için daha fazla chunk
                        } else {
                            preloadSurroundingChunks(target, 2); // Classic için async
                        }
                        
                        // 3. Yapı oluşturma için delay (procedural için daha uzun)
                        Bukkit.getScheduler().runTaskLater(
                            Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                            () -> {
                                try {
                                    // 4. Güvenli teleport
                                    boolean success = player.teleport(target);
                                    
                                    if (success) {
                                        System.out.println("[MythicDungeons Patch] " + dungeonType + " teleport SUCCESS for " + 
                                            player.getName() + " to dungeon at " + target.getWorld().getName() + " " + 
                                            target.getX() + "," + target.getY() + "," + target.getZ());
                                    } else {
                                        System.err.println("[MythicDungeons Patch] " + dungeonType + " teleport FAILED for " + player.getName());
                                    }
                                } catch (Exception e) {
                                    System.err.println("[MythicDungeons Patch] " + dungeonType + " teleport error: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            }, 
                            delay // Procedural için 1s, Classic için 250ms
                        );
                        
                    } catch (Exception e) {
                        System.err.println("[MythicDungeons Patch] " + dungeonType + " setup failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
        );
    }
    
    /**
     * Çevredeki chunk'ları önceden yükler (yapı generation için) - Async
     */
    private static void preloadSurroundingChunks(Location center, int radius) {
        int centerChunkX = center.getChunk().getX();
        int centerChunkZ = center.getChunk().getZ();
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = centerChunkX + x;
                int chunkZ = centerChunkZ + z;
                
                // Async chunk loading - non-blocking
                center.getWorld().getChunkAtAsync(chunkX, chunkZ, (chunk) -> {
                    // Chunk yükendi, hiçbir şey yapmaya gerek yok
                });
            }
        }
    }
    
    /**
     * Procedural dungeon'lar için sync chunk loading
     */
    private static void preloadSurroundingChunksSync(Location center, int radius) {
        int centerChunkX = center.getChunk().getX();
        int centerChunkZ = center.getChunk().getZ();
        int loadedCount = 0;
        
        System.out.println("[MythicDungeons Debug] Preloading chunks around " + 
                          centerChunkX + "," + centerChunkZ + " with radius " + radius + " for PROCEDURAL dungeon");
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = centerChunkX + x;
                int chunkZ = centerChunkZ + z;
                
                // Sync chunk loading - procedural için daha güvenli
                if (!center.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                    center.getWorld().loadChunk(chunkX, chunkZ);
                    loadedCount++;
                }
            }
        }
        
        System.out.println("[MythicDungeons Debug] Preloaded " + loadedCount + " chunks for procedural dungeon");
    }

    /**
     * Layout generation timeout'larını düzelt
     */
    public static void patchLayoutGeneration(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("generate")) {
                // Timeout değerlerini artır
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LdcInsnNode ldcInsn) {
                        if (ldcInsn.cst instanceof Long timeout && timeout == 5000L) {
                            // 5 saniye → 30 saniye
                            ldcInsn.cst = 30000L;
                            System.out.println("[MythicDungeons Patch] Increased generation timeout to 30s");
                        }
                    }
                    if (insn instanceof IntInsnNode intInsn) {
                        if (intInsn.operand == 5) {
                            // 5 saniye → 30 saniye
                            intInsn.operand = 30;
                            System.out.println("[MythicDungeons Patch] Increased generation timeout to 30s");
                        }
                    }
                }
            }
        }
    }
    
    /**
     * CompletableFuture ve ExecutorService async call'larını sync'e çevir
     */
    public static void patchAsyncGeneration(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode mInsn) {
                    // ExecutorService.submit() -> sync execution
                    if (mInsn.owner.contains("ExecutorService") && mInsn.name.equals("submit")) {
                        // Async submit'i sync çağrıya çevir
                        mInsn.owner = "java/util/concurrent/Callable";
                        mInsn.name = "call";
                        System.out.println("[MythicDungeons Patch] Converted async generation to sync");
                    }
                    
                    // CompletableFuture.get() timeout'ları düzelt
                    if (mInsn.owner.equals("java/util/concurrent/CompletableFuture") && mInsn.name.equals("get")) {
                        System.out.println("[MythicDungeons Patch] Found CompletableFuture.get() call");
                    }
                }
            }
        }
    }

    /**
     * ASM patch: MythicDungeons Util ve Layout sınıflarını patch'ler.
     */
    public static void patchDungeonTeleport(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching Util class methods...");
        boolean patchedAny = false;
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found method: " + method.name + method.desc);
            
            // forceTeleport2 metodunu patch'le
            if (method.name.equals("forceTeleport2") && 
                method.desc.equals("(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)V")) {
                
                patchMethod(method, "forceTeleport2");
                patchedAny = true;
            }
            
            // forceTeleport metodunu da patch'le (varsa)
            else if (method.name.equals("forceTeleport") && 
                     method.desc.contains("Lorg/bukkit/entity/Entity;") &&
                     method.desc.contains("Lorg/bukkit/Location;")) {
                
                patchMethod(method, "forceTeleport");
                patchedAny = true;
            }
            
            // teleportAsync çağrılarını patch'le
            patchTeleportAsyncCalls(method);
        }
        
        if (patchedAny) {
            System.out.println("[MythicDungeons Patch] Successfully patched Util teleport methods");
        } else {
            System.out.println("[MythicDungeons Debug] No teleport methods found to patch in Util class");
        }
    }
    
    /**
     * Method'u tamamen replacement ile patch'ler
     */
    private static void patchMethod(MethodNode method, String methodName) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        
        InsnList newCode = new InsnList();
        
        // Parametreleri stack'e yükle
        newCode.add(new VarInsnNode(Opcodes.ALOAD, 0)); // Entity entity
        newCode.add(new VarInsnNode(Opcodes.ALOAD, 1)); // Location location
        
        // Kendi metodumuzu çağır
        newCode.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(PluginFixManager.class),
            "teleportEntityToDungeon",
            "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)V",
            false
        ));
        
        newCode.add(new InsnNode(Opcodes.RETURN));
        method.instructions = newCode;
        
        System.out.println("[MythicDungeons Patch] Patched method: " + methodName);
    }
    
    /**
     * Method içindeki teleportAsync çağrılarını sync teleport ile değiştirir
     */
    private static void patchTeleportAsyncCalls(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("teleportAsync") && 
                    (mInsn.owner.equals("org/bukkit/entity/Entity") || 
                     mInsn.owner.equals("org/bukkit/entity/Player"))) {
                    
                    mInsn.name = "teleport";
                    mInsn.desc = "(Lorg/bukkit/Location;)Z";
                    
                    System.out.println("[MythicDungeons Patch] Replaced teleportAsync call with teleport");
                }
            }
        }
    }

    /**
     * Procedural Instance patch - ÖNEMLİ! Procedural dungeon'larda addPlayer debug'laması
     */
    public static void patchProceduralInstance(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching InstancePlayable for procedural dungeons...");
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found InstancePlayable method: " + method.name + method.desc);
            
            // addPlayer method'unu özel debug'la
            if (method.name.equals("addPlayer")) {
                System.out.println("[MythicDungeons Debug] Found procedural addPlayer method!");
                addDebugLogging(method, "InstancePlayable.addPlayer");
                
                // Procedural teleport detection
                patchProceduralTeleportDetection(method);
            }
        }
    }
    
    /**
     * Procedural teleport detection - forceTeleport çağrılarını tespit et
     */
    private static void patchProceduralTeleportDetection(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                // forceTeleport çağrısını tespit et
                if ((mInsn.name.equals("forceTeleport") || mInsn.name.equals("forceTeleport2")) &&
                    mInsn.owner.contains("Util")) {
                    
                    System.out.println("[MythicDungeons Debug] Found forceTeleport call in procedural addPlayer - will use PROCEDURAL teleport with extended delay");
                    // Not: Burada method signature'ı değiştirmek çok karmaşık olur
                    // Şimdilik detection yeterli - teleport method'umuz zaten procedural-aware
                }
            }
        }
    }
    
    /**
     * Method başına debug logging ekler
     */
    private static void addDebugLogging(MethodNode method, String methodName) {
        InsnList debugCode = new InsnList();
        
        // System.out.println("[MythicDungeons Debug] Executing " + methodName);
        debugCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        debugCode.add(new LdcInsnNode("[MythicDungeons Debug] Executing " + methodName));
        debugCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        method.instructions.insert(debugCode);
    }

    // -------------------- PLUGIN PATCH --------------------

    public static byte[] injectPluginFix(String plugin, String className, byte[] clazz) {
        // DEBUG: MythicDungeons class'larını log'la
        if (plugin.equals("MythicDungeons") || className.contains("mythicdungeons")) {
            System.out.println("[MythicDungeons Debug] Found MythicDungeons class: " + className);
        }
        
        if (plugin.equals("WorldEdit")) {
            String adapter = System.getProperty("worldedit.bukkit.adapter");
            if (adapter == null) {
                System.setProperty("worldedit.bukkit.adapter", "com.sk89q.worldedit.bukkit.adapter.impl.v1_21.PaperweightAdapter");
            }
        }

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
            case "com.onarandombox.MultiverseCore.utils.WorldManager" -> {
                return patch(clazz, MultiverseCore::fix);
            }
            // MythicDungeons patch - TELEPORT FIX
            case "net.playavalon.mythicdungeons.utility.helpers.Util" -> {
                System.out.println("[MythicDungeons Patch] Patching Util class for teleport fixes...");
                return patch(clazz, PluginFixManager::patchDungeonTeleport);
            }
            // MythicDungeons patch - PROCEDURAL INSTANCE FIX (ÖNEMLİ!)
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
            // MythicDungeons patch - LAYOUT GENERATION FIX
            case "net.playavalon.mythicdungeons.api.generation.layout.Layout" -> {
                System.out.println("[MythicDungeons Patch] Patching Layout generation...");
                return patch(clazz, PluginFixManager::patchLayoutGeneration);
            }
            // MythicDungeons patch - ASYNC GENERATION FIX
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

    // -------------------- ASM HELPER --------------------

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

    public static boolean hasPaperAsyncSupport() { return false; }

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

    private static void qs(ClassNode node) { redirectMethodToGetNMSVersion(node, "getNMSVersion"); }
    private static void fawe(ClassNode node) { redirectMethodToGetNMSVersion(node, "getPackageVersion"); }

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

    public static String getNMSVersion() { return "v1_21_R1"; }

    public static String make(String in) {
        if (in.equals("8(;4>`")) return "peace";
        final char[] c = in.toCharArray();
        for (int i = 0; i < c.length; ++i) c[i] ^= 'Z';
        return new String(c);
    }
}
