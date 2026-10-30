package com.jalebismp.settings.feature;

import com.jalebismp.settings.JalebiSettings;
import com.jalebismp.settings.Msg;
import com.jalebismp.settings.data.Setting;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Light PvP tag that drives the "Combat Timer" visual setting (boss bar). */
public class CombatTracker implements Listener, Runnable {
    private final JalebiSettings plugin;
    private final Map<UUID, Long> expiry = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public CombatTracker(JalebiSettings plugin) { this.plugin = plugin; }

    public boolean isInCombat(UUID id) {
        Long t = expiry.get(id);
        return t != null && t > System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;

        long until = System.currentTimeMillis() + plugin.getConfig().getLong("combat.duration-seconds", 15) * 1000L;
        expiry.put(victim.getUniqueId(), until);
        expiry.put(attacker.getUniqueId(), until);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { untag(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { untag(e.getPlayer().getUniqueId()); }

    private void untag(UUID id) {
        expiry.remove(id);
        BossBar bar = bars.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (bar != null && p != null) p.hideBossBar(bar);
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        double total = plugin.getConfig().getLong("combat.duration-seconds", 15) * 1000.0;
        Iterator<Map.Entry<UUID, Long>> it = expiry.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            UUID id = en.getKey();
            Player p = Bukkit.getPlayer(id);
            long remaining = en.getValue() - now;
            boolean wantBar = p != null && plugin.data().on(id, Setting.COMBAT_TIMER);

            if (p == null || remaining <= 0) {
                it.remove();
                BossBar bar = bars.remove(id);
                if (p != null) {
                    if (bar != null) p.hideBossBar(bar);
                    if (wantBar) Msg.send(p, "<green>You are no longer in combat.");
                }
                continue;
            }
            if (wantBar) {
                BossBar bar = bars.computeIfAbsent(id, k ->
                        BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
                bar.name(Component.text("In combat: " + (int) Math.ceil(remaining / 1000.0) + "s", NamedTextColor.RED));
                bar.progress((float) Math.max(0, Math.min(1, remaining / total)));
                p.showBossBar(bar);
            } else {
                BossBar bar = bars.remove(id);
                if (bar != null) p.hideBossBar(bar);
            }
        }
    }
}
