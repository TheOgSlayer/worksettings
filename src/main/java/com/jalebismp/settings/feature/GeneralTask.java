package com.jalebismp.settings.feature;

import com.jalebismp.settings.JalebiSettings;
import com.jalebismp.settings.data.Setting;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Night Vision, Mob Spawns and Phantom Spawning. Runs on the main thread. */
public class GeneralTask implements Runnable, Listener {
    private final JalebiSettings plugin;
    private final NamespacedKey tag;
    private final Random rnd = new Random();
    private final List<EntityType> mobTypes = new ArrayList<>();

    public GeneralTask(JalebiSettings plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "settings_spawned");
        for (String s : plugin.getConfig().getStringList("mob-spawns.types")) {
            try { mobTypes.add(EntityType.valueOf(s.toUpperCase())); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Unknown mob type in config: " + s); }
        }
        if (mobTypes.isEmpty()) mobTypes.add(EntityType.ZOMBIE);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { applyNightVision(e.getPlayer(), false); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> applyNightVision(e.getPlayer(), false));
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            applyNightVision(p, false);
            if (plugin.data().on(p.getUniqueId(), Setting.MOB_SPAWNS)) spawnMobs(p);
            if (plugin.data().on(p.getUniqueId(), Setting.PHANTOM_SPAWNING)) spawnPhantoms(p);
        }
    }

    public void applyNightVision(Player p, boolean removeIfOff) {
        boolean want = plugin.data().on(p.getUniqueId(), Setting.NIGHT_VISION);
        PotionEffect cur = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (want) {
            if (cur == null || !cur.isInfinite())
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } else if (removeIfOff && cur != null && cur.isInfinite()) {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    private long countTagged(Player p, String kind, double radius) {
        return p.getNearbyEntities(radius, radius / 2, radius).stream()
                .filter(en -> kind.equals(en.getPersistentDataContainer().get(tag, PersistentDataType.STRING)))
                .count();
    }

    private void spawnMobs(Player p) {
        FileConfiguration c = plugin.getConfig();
        int max = c.getInt("mob-spawns.max-nearby", 8);
        int per = c.getInt("mob-spawns.per-cycle", 2);
        double min = c.getDouble("mob-spawns.min-distance", 10);
        double maxDist = c.getDouble("mob-spawns.max-distance", 24);

        long nearby = countTagged(p, "mob", 48);
        for (int i = 0; i < per && nearby < max; i++) {
            Location loc = findGround(p.getLocation(), min, maxDist);
            if (loc == null) continue;
            EntityType type = mobTypes.get(rnd.nextInt(mobTypes.size()));
            Entity en = loc.getWorld().spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            en.getPersistentDataContainer().set(tag, PersistentDataType.STRING, "mob");
            if (en instanceof Mob m) m.setTarget(p);
            nearby++;
        }
    }

    private void spawnPhantoms(Player p) {
        if (p.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        FileConfiguration c = plugin.getConfig();
        int max = c.getInt("phantom-spawns.max-nearby", 3);
        int per = c.getInt("phantom-spawns.per-cycle", 1);

        long nearby = countTagged(p, "phantom", 64);
        for (int i = 0; i < per && nearby < max; i++) {
            Location base = p.getLocation();
            Location loc = base.clone().add(rnd.nextInt(25) - 12, 12 + rnd.nextInt(9), rnd.nextInt(25) - 12);
            if (!loc.getBlock().isPassable() || !loc.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            Entity en = loc.getWorld().spawnEntity(loc, EntityType.PHANTOM, CreatureSpawnEvent.SpawnReason.CUSTOM);
            en.getPersistentDataContainer().set(tag, PersistentDataType.STRING, "phantom");
            if (en instanceof Mob m) m.setTarget(p);
            nearby++;
        }
    }

    /** Finds a standable spot 'min'..'max' blocks away horizontally, within +-6 blocks of the player's height. */
    private Location findGround(Location base, double min, double max) {
        World w = base.getWorld();
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = min + rnd.nextDouble() * Math.max(1, max - min);
            int x = (int) Math.floor(base.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(base.getZ() + Math.sin(ang) * dist);
            int by = base.getBlockY();
            for (int y = by + 6; y >= by - 6; y--) {
                if (y < w.getMinHeight() || y + 2 >= w.getMaxHeight()) continue;
                Block floor = w.getBlockAt(x, y, z);
                Block a = w.getBlockAt(x, y + 1, z);
                Block b = w.getBlockAt(x, y + 2, z);
                if (floor.getType().isSolid() && a.isPassable() && b.isPassable() && !a.isLiquid() && !b.isLiquid())
                    return new Location(w, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }
}
