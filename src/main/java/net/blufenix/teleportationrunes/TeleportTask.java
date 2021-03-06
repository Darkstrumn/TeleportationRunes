package net.blufenix.teleportationrunes;

import net.blufenix.common.Log;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;

public class TeleportTask extends BukkitRunnable {

    // normally this would need to be thread safe, but minecraft only runs on one thread
    private static Set<Player> playersCurrentlyTeleporting = new HashSet<>();

    // modify these
    private static final int COUNTDOWN_SECONDS = 3;
    private static final int UPDATE_INTERVAL_TICKS = 2;

    // auto-calculated
    // todo de-dupe this calc
    private static final int COUNTDOWN_TICKS = COUNTDOWN_SECONDS * 20; // assumes 20 ticks per second standard server
    private final Callback callback;

    private int elapsedTicks = 0;

    private final Player player;
    private final boolean canLeaveArea;
    private final boolean requireSneak;
    private Location sourceLoc;
    private Waypoint destWaypoint;
    private Location potentialTeleporterLoc;
    private Signature waypointSignature;

    private SwirlAnimation animation;
    private boolean isRunning;

    TeleportTask(Player player, Location potentialTeleporterLoc, Callback callback) {
        this.player = player;
        this.callback = callback;
        this.potentialTeleporterLoc = potentialTeleporterLoc;
        canLeaveArea = false;
        requireSneak = false;
    }

    TeleportTask(Player player, Signature waypointSignature, boolean requireSneak, Callback callback) {
        this.player = player;
        this.callback = callback;
        this.waypointSignature = waypointSignature;
        this.canLeaveArea = true;
        this.requireSneak = requireSneak;
    }

    private void lateInit() {
        if (potentialTeleporterLoc != null) {
            Teleporter teleporter = TeleUtils.getTeleporterNearLocation(potentialTeleporterLoc);
            this.sourceLoc = teleporter != null ? teleporter.loc : null;
            this.destWaypoint = TeleUtils.getWaypointForTeleporter(teleporter);
        } else if (waypointSignature != null) {
            this.sourceLoc = player.getLocation();
            this.destWaypoint = TeleUtils.getWaypointForSignature(waypointSignature);
        } else {
            throw new RuntimeException("lateInit() failed. bad params?");
        }
    }

    public void execute() {
        if (playersCurrentlyTeleporting.contains(player)) {
            return; //todo this will mean our callback isn't called
            // but we need it for now in order to prevent repeated teleport attempts
            // since calling onSuccessOrFail will remove us from playersCurrentlyTeleporting
            // and right now the callbacks don't need to work in that case.
        }

        playersCurrentlyTeleporting.add(player);
        lateInit();

        if (startTeleportationTask()) {
            isRunning = true;
        } else {
            onSuccessOrFail(false);
        }
    }

    private boolean startTeleportationTask() {
        try {
            if (sourceLoc == null || destWaypoint == null) return false;

            // show the player the cost
            int fee = TeleUtils.calculateFee(destWaypoint.loc, sourceLoc);
            int currentExp = ExpUtil.getTotalExperience(player);
            String msg = String.format("%d XP / %d XP", fee, currentExp);
            //player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
            player.sendTitle("", msg);

            if (requireSneak && !player.isSneaking()) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("[sneak to confirm]"));
            } else {
                // start teleport animation and timer
                animation = SwirlAnimation.getDefault();
                if (canLeaveArea) {
                    animation.setLocation(player);
                } else {
                    // make the animation occur at the player's height
                    // in case they are hiding the teleporter under a layer of blocks
                    Location animLoc = sourceLoc.clone();
                    animLoc.setY(player.getLocation().getY());
                    animation.setLocation(animLoc);
                }

                runTaskTimer(TeleportationRunes.getInstance(), 0, UPDATE_INTERVAL_TICKS);
                return true;
            }
        } catch (Exception e) {
            Log.e("error in startTeleportationTask!", e);
        }

        return false;
    }

    @Override
    public void run() {

        // we haven't actually ticked yet, but if we pass 0 into our animation ticks
        // it will animate a single frame regardless of the interval or fake tick settings
        // TODO is there a cleaner way to fix this and remove the time shift?
        elapsedTicks += UPDATE_INTERVAL_TICKS;

        if (!canLeaveArea && !playerStillAtTeleporter()) {
            player.sendMessage("You left the teleporter area. Cancelling...");
            onSuccessOrFail(false);
            return;
        }

        if (elapsedTicks < COUNTDOWN_TICKS) {
            animation.update(elapsedTicks);
        } else {
            if (TeleUtils.attemptTeleport(player, sourceLoc, destWaypoint)) {
                onSuccessOrFail(true);
            } else {
                onSuccessOrFail(false);
            }
        }

    }

    private void onSuccessOrFail(boolean success) {
        if (isRunning) this.cancel();
        if (callback != null) {
            callback.onFinished(success);
        }
        playersCurrentlyTeleporting.remove(player);
    }

    private boolean playerStillAtTeleporter() {
        return player.getLocation().distance(sourceLoc) < 2.5;
    }

    public static abstract class Callback {
        abstract void onFinished(boolean success);
    }
}