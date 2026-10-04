package pl.sesion16.tpaplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

public class TPAPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, TPARequest> pendingRequests = new HashMap<>();
    private final Map<UUID, TeleportSession> activeTeleports = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("tpa")).setExecutor(this);
        Objects.requireNonNull(getCommand("tpaccept")).setExecutor(this);
        Objects.requireNonNull(getCommand("tpadeny")).setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (TPARequest request : pendingRequests.values()) {
            request.cancelTimeoutTask();
        }
        for (TeleportSession session : activeTeleports.values()) {
            session.cancelTask();
        }
        pendingRequests.clear();
        activeTeleports.clear();
    }

    private Component color(String text) {
        if (text == null) return Component.empty();
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private String getPrefixedMessage(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&aTPA&8] ");
        String msg = getConfig().getString("messages." + path, "");
        return prefix + msg;
    }

    private void showTitle(Player player, String titleText, String subtitleText) {
        Title title = Title.title(
                color(titleText),
                color(subtitleText),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(300))
        );
        player.showTitle(title);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(getConfig().getString("messages.player-only", "Tylko dla graczy!")));
            return true;
        }

        if (command.getName().equalsIgnoreCase("tpa")) {
            if (args.length < 1) {
                player.sendMessage(color(getPrefixedMessage("usage-tpa")));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                String msg = getPrefixedMessage("player-not-found").replace("%player%", args[0]);
                player.sendMessage(color(msg));
                return true;
            }

            if (target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(color(getPrefixedMessage("cannot-tpa-self")));
                return true;
            }

            if (pendingRequests.containsKey(target.getUniqueId())) {
                pendingRequests.get(target.getUniqueId()).cancelTimeoutTask();
            }

            int timeoutSeconds = getConfig().getInt("settings.request-timeout", 60);

            BukkitTask timeoutTask = new BukkitRunnable() {
                @Override
                public void run() {
                    pendingRequests.remove(target.getUniqueId());
                    if (player.isOnline()) {
                        String msg = getPrefixedMessage("request-expired").replace("%player%", target.getName());
                        player.sendMessage(color(msg));
                    }
                }
            }.runTaskLater(this, timeoutSeconds * 20L);

            TPARequest request = new TPARequest(player.getUniqueId(), target.getUniqueId(), timeoutTask);
            pendingRequests.put(target.getUniqueId(), request);

            String sentMsg = getPrefixedMessage("request-sent").replace("%player%", target.getName());
            player.sendMessage(color(sentMsg));

            Component receivedMsg = color(getPrefixedMessage("request-received").replace("%player%", player.getName()));
            Component button = color(getConfig().getString("messages.accept-button", "&a&l[KLIKNIJ TUTAJ, ABY ZAAKCEPTOWAĆ]"))
                    .clickEvent(ClickEvent.runCommand("/tpaccept"))
                    .hoverEvent(HoverEvent.showText(color(getConfig().getString("messages.accept-hover", "&7Kliknij, aby zaakceptować"))));

            target.sendMessage(receivedMsg.append(button));
            return true;

        } else if (command.getName().equalsIgnoreCase("tpaccept")) {
            TPARequest request = pendingRequests.remove(player.getUniqueId());

            if (request == null) {
                player.sendMessage(color(getPrefixedMessage("no-pending-request")));
                return true;
            }

            request.cancelTimeoutTask();
            Player requester = Bukkit.getPlayer(request.getRequesterUUID());

            if (requester == null || !requester.isOnline()) {
                player.sendMessage(color(getPrefixedMessage("player-not-found").replace("%player%", "gracz")));
                return true;
            }

            int delay = getConfig().getInt("settings.teleport-delay", 10);

            String targetMsg = getPrefixedMessage("request-accepted-target").replace("%player%", requester.getName());
            player.sendMessage(color(targetMsg));

            String senderMsg = getPrefixedMessage("request-accepted-sender")
                    .replace("%player%", player.getName())
                    .replace("%delay%", String.valueOf(delay));
            requester.sendMessage(color(senderMsg));

            startTeleportCountdown(requester, player);
            return true;

        } else if (command.getName().equalsIgnoreCase("tpadeny")) {
            TPARequest request = pendingRequests.remove(player.getUniqueId());

            if (request == null) {
                player.sendMessage(color(getPrefixedMessage("no-pending-request")));
                return true;
            }

            request.cancelTimeoutTask();
            Player requester = Bukkit.getPlayer(request.getRequesterUUID());

            String targetMsg = getPrefixedMessage("request-denied-target").replace("%player%", requester != null ? requester.getName() : "gracz");
            player.sendMessage(color(targetMsg));

            if (requester != null && requester.isOnline()) {
                String senderMsg = getPrefixedMessage("request-denied-sender").replace("%player%", player.getName());
                requester.sendMessage(color(senderMsg));
            }
            return true;
        }

        return false;
    }

    private void startTeleportCountdown(Player requester, Player target) {
        if (activeTeleports.containsKey(requester.getUniqueId())) {
            activeTeleports.get(requester.getUniqueId()).cancelTask();
            activeTeleports.remove(requester.getUniqueId());
        }

        TeleportSession session = new TeleportSession(requester, target);
        activeTeleports.put(requester.getUniqueId(), session);

        int totalSeconds = getConfig().getInt("settings.teleport-delay", 10);

        BukkitTask task = new BukkitRunnable() {
            int secondsLeft = totalSeconds;

            @Override
            public void run() {
                if (!requester.isOnline()) {
                    activeTeleports.remove(requester.getUniqueId());
                    cancel();
                    return;
                }

                if (!target.isOnline()) {
                    activeTeleports.remove(requester.getUniqueId());
                    requester.sendMessage(color(getPrefixedMessage("player-not-found").replace("%player%", target.getName())));
                    cancel();
                    return;
                }

                if (secondsLeft > 0) {
                    String title = getConfig().getString("titles.teleporting.title", "&eTeleportacja...");
                    String subtitle = getConfig().getString("titles.teleporting.subtitle", "&7Za &c%seconds%s &7nie ruszaj się!")
                            .replace("%seconds%", String.valueOf(secondsLeft));
                    showTitle(requester, title, subtitle);
                    secondsLeft--;
                } else {
                    requester.teleport(target.getLocation());
                    String title = getConfig().getString("titles.teleport-success.title", "&aPrzeteleportowano!");
                    String subtitle = getConfig().getString("titles.teleport-success.subtitle", "&7Pomyślnie przeteleportowano do &e%player%")
                            .replace("%player%", target.getName());
                    showTitle(requester, title, subtitle);
                    activeTeleports.remove(requester.getUniqueId());
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);

        session.setTask(task);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("settings.cancel-on-move", true)) return;

        Player player = event.getPlayer();
        if (!activeTeleports.containsKey(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            TeleportSession session = activeTeleports.remove(player.getUniqueId());
            if (session != null) {
                session.cancelTask();
            }

            String title = getConfig().getString("titles.teleport-cancelled-move.title", "&cTeleportacja przerwana!");
            String subtitle = getConfig().getString("titles.teleport-cancelled-move.subtitle", "&7Poruszyłeś się!");
            showTitle(player, title, subtitle);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingRequests.remove(uuid);

        TeleportSession session = activeTeleports.remove(uuid);
        if (session != null) {
            session.cancelTask();
        }
    }

    private static class TPARequest {
        private final UUID requesterUUID;
        private final UUID targetUUID;
        private final BukkitTask timeoutTask;

        public TPARequest(UUID requesterUUID, UUID targetUUID, BukkitTask timeoutTask) {
            this.requesterUUID = requesterUUID;
            this.targetUUID = targetUUID;
            this.timeoutTask = timeoutTask;
        }

        public UUID getRequesterUUID() {
            return requesterUUID;
        }

        public void cancelTimeoutTask() {
            if (timeoutTask != null) {
                timeoutTask.cancel();
            }
        }
    }

    private static class TeleportSession {
        private final Player requester;
        private final Player target;
        private BukkitTask task;

        public TeleportSession(Player requester, Player target) {
            this.requester = requester;
            this.target = target;
        }

        public void setTask(BukkitTask task) {
            this.task = task;
        }

        public void cancelTask() {
            if (task != null) {
                task.cancel();
            }
        }
    }
}
