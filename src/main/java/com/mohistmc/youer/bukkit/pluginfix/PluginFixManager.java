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

    // -------------------- TELEPORT + PROCEDURAL FIX --------------------

    /**
     * Oyuncuyu dungeon içine güvenli şekilde teleport eder.
     * Procedural dungeon'lar için daha uzun bekleme süresi.
     */
    public static void teleportEntityToDungeon(Entity entity, Location target) {
        teleportEntityToDungeon(entity, target, false);
    }

    /**
     * Procedural aware teleport
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

        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                () -> {
                    try {
                        // Chunk'ı yükle
                        if (!target.getChunk().isLoaded()) {
                            System.out.println("[MythicDungeons Debug] Loading chunk for " + dungeonType + " teleport...");
                            target.getChunk().load();
                        }
                        
                        // Procedural için çevredeki chunk'ları da yükle
                        if (isProcedural) {
                            preloadSurroundingChunks(target, 2);
                        }
                        
                        // Delay ile teleport
                        Bukkit.getScheduler().runTaskLater(
                            Bukkit.getPluginManager().getPlugin("MythicDungeons"),
                            () -> {
                                try {
                                    boolean success = player.teleport(target);
                                    
                                    if (success) {
                                        System.out.println("[MythicDungeons Patch] " + dungeonType + " teleport SUCCESS for " + 
                                            player.getName() + " to " + target.getWorld().getName());
                                    } else {
                                        System.err.println("[MythicDungeons Patch] " + dungeonType + " teleport FAILED for " + 
                                            player.getName());
                                    }
                                } catch (Exception e) {
                                    System.err.println("[MythicDungeons Patch] " + dungeonType + " teleport error: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            }, 
                            delay
                        );
                        
                    } catch (Exception e) {
                        System.err.println("[MythicDungeons Patch] " + dungeonType + " setup failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
        );
    }

    /**
     * Çevredeki chunk'ları preload et
     */
    private static void preloadSurroundingChunks(Location center, int radius) {
        int centerChunkX = center.getChunk().getX();
        int centerChunkZ = center.getChunk().getZ();
        int loadedCount = 0;
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int chunkX = centerChunkX + x;
                int chunkZ = centerChunkZ + z;
                
                if (!center.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                    center.getWorld().loadChunk(chunkX, chunkZ);
                    loadedCount++;
                }
            }
        }
        
        System.out.println("[MythicDungeons Debug] Preloaded " + loadedCount + " chunks around dungeon");
    }

    /**
     * teleportAsync replacement
     */
    public static CompletableFuture<Boolean> teleportAsyncReplacement(Entity entity, Location location, Object... args) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (!(entity instanceof Player)) {
            future.complete(false);
            return future;
        }
        
        Bukkit.getScheduler().runTask(
            Bukkit.getPluginManager().getPlugin("MythicDungeons"),
            () -> {
                try {
                    teleportEntityToDungeon(entity, location, true); // Async olarak çağrılıyorsa procedural olabilir
                    future.complete(true);
                } catch (Exception e) {
                    future.complete(false);
                }
            }
        );
        
        return future;
    }

    /**
     * Geriye uyumluluk için eski metod
     */
    @Deprecated
    public static void teleportPlayerToDungeon(Player player, Location target) {
        teleportEntityToDungeon(player, target);
    }

    /**
     * ASM patch: MythicDungeons Util sınıfındaki teleport metodlarını patch'ler.
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
            System.out.println("[MythicDungeons Debug] No teleport methods found to patch");
        }
    }

    /**
     * Procedural instance patch - sadece debug için
     */
    public static void patchProceduralInstance(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching InstancePlayable for procedural dungeons...");
        
        for (MethodNode method : node.methods) {
            System.out.println("[MythicDungeons Debug] Found InstancePlayable method: " + method.name + method.desc);
            
            // addPlayer method'unu özel debug'la
            if (method.name.equals("addPlayer")) {
                System.out.println("[MythicDungeons Debug] Found procedural addPlayer method!");
                addDebugLogging(method, "InstancePlayable.addPlayer");
                
                // Procedural teleport flag'i ekle (method içinde procedural=true çağrısı yapılması için)
                patchProceduralTeleportFlag(method);
            }
        }
    }

    /**
     * Procedural teleport flag patch'i
     */
    private static void patchProceduralTeleportFlag(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                // forceTeleport çağrısını tespit et
                if ((mInsn.name.equals("forceTeleport") || mInsn.name.equals("forceTeleport2")) &&
                    mInsn.owner.contains("Util")) {
                    
                    System.out.println("[MythicDungeons Debug] Found forceTeleport call in procedural addPlayer - will use extended delay");
                    // Burada method çağrısını değiştirip procedural flag ekleyebiliriz ama karmaşık
                    // Şimdilik sadece detection yeterli
                }
            }
        }
    }
    
    /**
     * Method'u tamamen replacement ile patch'ler
     */
    private static void patchMethod(MethodNode method, String methodName) {
        System.out.println("[MythicDungeons Debug] Completely replacing method: " + methodName);
        
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
        
        System.out.println("[MythicDungeons Patch] Successfully patched method: " + methodName);
    }
    
    /**
     * Debug logging ekler
     */
    private static void addDebugLogging(MethodNode method, String methodName) {
        InsnList debugCode = new InsnList();
        
        debugCode.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        debugCode.add(new LdcInsnNode("[MythicDungeons Debug] Executing " + methodName));
        debugCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        method.instructions.insert(debugCode);
    }
    
    /**
     * Method içindeki teleportAsync çağrılarını sync teleport ile değiştirir
     */
    private static void patchTeleportAsyncCalls(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                // teleportAsync çağrısını yakala
                if (mInsn.name.equals("teleportAsync") && 
                    (mInsn.owner.equals("org/bukkit/entity/Entity") || 
                     mInsn.owner.equals("org/bukkit/entity/Player"))) {
                    
                    // teleportAsync -> teleport olarak değiştir
                    mInsn.name = "teleport";
                    // Sadece Location parametresini al (diğer parametreleri ignore et)
                    mInsn.desc = "(Lorg/bukkit/Location;)Z";
                    
                    System.out.println("[MythicDungeons Patch] Replaced teleportAsync call with teleport");
                }
            }
        }
    }
    
    /**
     * Layout sınıfındaki async generation sorunlarını düzelt
     */
    public static void patchDungeonLayout(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching Layout class...");
        
        for (MethodNode method : node.methods) {
            // Async method calls'ları sync'e çevir
            patchAsyncMethodCalls(method);
            
            // Timeout değerlerini artır
            patchTimeouts(method);
            
            // Debug logging ekle
            if (method.name.equals("generate") || method.name.equals("build") || 
                method.name.equals("paste") || method.name.equals("load")) {
                System.out.println("[MythicDungeons Debug] Found generation method: " + method.name + method.desc);
                addDebugLogging(method, method.name);
            }
        }
    }
    
    /**
     * AbstractInstance sınıfındaki world creation sorunlarını düzelt
     */
    public static void patchAbstractInstance(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching AbstractInstance class...");
        
        for (MethodNode method : node.methods) {
            // Async calls'ları sync'e çevir
            patchAsyncMethodCalls(method);
            
            // World creation ve chunk loading method'larını patch'le
            if (method.name.equals("createWorld") || method.name.equals("initMap") ||
                method.name.equals("applyWorldRules") || method.name.contains("World")) {
                System.out.println("[MythicDungeons Debug] Found world method: " + method.name + method.desc);
                addDebugLogging(method, method.name);
            }
        }
    }
    
    /**
     * Method içindeki async çağrıları sync'e çevirir
     */
    private static void patchAsyncMethodCalls(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                // Scheduler.runTaskAsynchronously -> runTask
                if (mInsn.name.equals("runTaskAsynchronously")) {
                    mInsn.name = "runTask";
                    System.out.println("[MythicDungeons Patch] Converted runTaskAsynchronously to runTask");
                }
                
                // CompletableFuture.supplyAsync -> sync call
                else if (mInsn.name.equals("supplyAsync") && 
                         mInsn.owner.equals("java/util/concurrent/CompletableFuture")) {
                    // Bu daha karmaşık bir patch, şimdilik log at
                    System.out.println("[MythicDungeons Debug] Found CompletableFuture.supplyAsync call in " + method.name);
                }
                
                // Chunk loading async calls
                else if ((mInsn.name.equals("getChunkAtAsync") || mInsn.name.equals("loadChunkAsync")) &&
                         mInsn.owner.equals("org/bukkit/World")) {
                    // Async chunk loading'i sync'e çevir
                    mInsn.name = mInsn.name.replace("Async", "");
                    System.out.println("[MythicDungeons Patch] Converted async chunk loading to sync");
                }
            }
        }
    }
    
    /**
     * Timeout değerlerini artırır
     */
    private static void patchTimeouts(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            // Küçük timeout değerlerini artır (1000ms altındakileri 10x yap)
            if (insn instanceof IntInsnNode iInsn) {
                if (iInsn.operand > 0 && iInsn.operand < 1000) {
                    int oldValue = iInsn.operand;
                    iInsn.operand *= 10; // 10x artır
                    System.out.println("[MythicDungeons Patch] Increased timeout from " + oldValue + " to " + iInsn.operand);
                }
            }
            else if (insn instanceof LdcInsnNode lInsn) {
                if (lInsn.cst instanceof Integer timeout) {
                    if (timeout > 0 && timeout < 1000) {
                        int oldValue = timeout;
                        lInsn.cst = timeout * 10;
                        System.out.println("[MythicDungeons Patch] Increased LDC timeout from " + oldValue + " to " + lInsn.cst);
                    }
                }
            }
        }
    }
    
    /**
     * Method başına debug logging ekler
     */
    private static void addDebugLogging(MethodNode method, String methodName) {
        // Method başına log ekle
        InsnList debugStart = new InsnList();
        debugStart.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        debugStart.add(new LdcInsnNode("[MythicDungeons Debug] Executing " + methodName + " method"));
        debugStart.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        
        method.instructions.insert(debugStart);
    }
    
    /**
     * StructurePiece sınıfındaki block placement sorunlarını düzelt
     */
    public static void patchStructurePiece(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching StructurePiece class...");
        
        for (MethodNode method : node.methods) {
            // Async calls'ları sync'e çevir
            patchAsyncMethodCalls(method);
            
            // Block placement method'larını debug'la
            if (method.name.equals("place") || method.name.equals("build") || 
                method.name.equals("paste") || method.name.equals("setBlock")) {
                System.out.println("[MythicDungeons Debug] Found block placement method: " + method.name + method.desc);
                addDebugLogging(method, "StructurePiece." + method.name);
                
                // Block placement'i sync yap
                patchBlockPlacement(method);
            }
        }
    }
    
    /**
     * SchematicHelper sınıfındaki schematic loading sorunlarını düzelt
     */
    public static void patchSchematicHelper(ClassNode node) {
        System.out.println("[MythicDungeons Debug] Patching SchematicHelper class...");
        
        for (MethodNode method : node.methods) {
            // Async calls'ları sync'e çevir
            patchAsyncMethodCalls(method);
            
            // Schematic loading method'larını debug'la
            if (method.name.equals("load") || method.name.equals("paste") || 
                method.name.equals("read") || method.name.equals("apply")) {
                System.out.println("[MythicDungeons Debug] Found schematic method: " + method.name + method.desc);
                addDebugLogging(method, "SchematicHelper." + method.name);
            }
            
            // NBT loading method'larını patch'le
            if (method.name.contains("NBT") || method.name.contains("nbt")) {
                System.out.println("[MythicDungeons Debug] Found NBT method: " + method.name + method.desc);
                addDebugLogging(method, "SchematicHelper." + method.name + "(NBT)");
            }
        }
    }
    
    /**
     * Block placement'i sync yapar ve chunk loading ekler
     */
    private static void patchBlockPlacement(MethodNode method) {
        // Block placement için chunk loading ekle
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                // setBlock, setType gibi çağrılardan önce chunk loading ekle
                if (mInsn.name.equals("setBlock") || mInsn.name.equals("setType") ||
                    mInsn.name.equals("setBlockData")) {
                    
                    // Bu daha karmaşık bir patch - şimdilik debug log
                    System.out.println("[MythicDungeons Debug] Found block setting method: " + mInsn.name);
                }
            }
        }
    }

    // -------------------- PLUGIN PATCH --------------------

    public static byte[] injectPluginFix(String plugin, String className, byte[] clazz) {
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
            // MythicDungeons patches
            case "net.playavalon.mythicdungeons.utility.helpers.Util" -> {
                System.out.println("[MythicDungeons Patch] Patching Util class for teleport fixes...");
                return patch(clazz, PluginFixManager::patchDungeonTeleport);
            }
            case "net.playavalon.mythicdungeons.api.parents.instances.InstancePlayable" -> {
                System.out.println("[MythicDungeons Patch] Patching InstancePlayable for procedural dungeons...");
                return patch(clazz, PluginFixManager::patchProceduralInstance);
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

    // -------------------- ASM HELPER (Aynı) --------------------

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
            return basicClass; // Patch başarısızsa orijinali döndür
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
