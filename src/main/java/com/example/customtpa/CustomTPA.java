package com.example.customtpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CustomTPA extends JavaPlugin implements CommandExecutor {

    // Mapa: Odbiorca (Target) -> Nadawca (Requester)
    private final Map<UUID, UUID> tpaRequests = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("tpa") != null) getCommand("tpa").setExecutor(this);
        if (getCommand("tpaccept") != null) getCommand("tpaccept").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(getMessage("messages.only-players"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("tpa")) {
            if (args.length < 1) {
                player.sendMessage(getMessage("messages.tpa-usage"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                player.sendMessage(getMessage("messages.player-not-found"));
                return true;
            }

            if (target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(getMessage("messages.cannot-tpa-self"));
                return true;
            }

            tpaRequests.put(target.getUniqueId(), player.getUniqueId());

            String sentMsg = getConfig().getString("messages.request-sent", "&aWysłano prośbę do {player}")
                    .replace("{player}", target.getName());
            player.sendMessage(parseColor(sentMsg));

            String receivedMsg = getConfig().getString("messages.request-received", "&aProśba od {player}")
                    .replace("{player}", player.getName());
            target.sendMessage(parseColor(receivedMsg));

            return true;
        }

        if (command.getName().equalsIgnoreCase("tpaccept")) {
            UUID requesterUUID = tpaRequests.get(player.getUniqueId());
            if (requesterUUID == null) {
                player.sendMessage(getMessage("messages.no-pending-request"));
                return true;
            }

            Player requester = Bukkit.getPlayer(requesterUUID);
            tpaRequests.remove(player.getUniqueId());

            if (requester == null || !requester.isOnline()) {
                player.sendMessage(getMessage("messages.player-not-found"));
                return true;
            }

            int delay = getConfig().getInt("teleport-delay", 10);

            String acceptedSenderMsg = getConfig().getString("messages.request-accepted-sender", "&aZaakceptowano!")
                    .replace("{player}", player.getName())
                    .replace("{delay}", String.valueOf(delay));
            requester.sendMessage(parseColor(acceptedSenderMsg));

            String acceptedTargetMsg = getConfig().getString("messages.request-accepted-target", "&aZaakceptowano!")
                    .replace("{player}", requester.getName());
            player.sendMessage(parseColor(acceptedTargetMsg));

            startTeleportCountdown(requester, player, delay);
            return true;
        }

        return false;
    }

    private void startTeleportCountdown(Player requester, Player target, int delay) {
        new BukkitRunnable() {
            int secondsLeft = delay;

            @Override
            public void run() {
                if (!requester.isOnline() || !target.isOnline()) {
                    cancel();
                    return;
                }

                if (secondsLeft > 0) {
                    String subMsg = getConfig().getString("title.subtitle", "&eSekund do teleportacji");
                    Title title = Title.title(
                            parseColor("&6" + secondsLeft),
                            parseColor(subMsg),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
                    );
                    requester.showTitle(title);
                    secondsLeft--;
                } else {
                    requester.teleport(target.getLocation());
                    requester.sendMessage(getMessage("messages.teleport-success"));
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private Component getMessage(String path) {
        String msg = getConfig().getString(path, "");
        return parseColor(msg);
    }

    private Component parseColor(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
