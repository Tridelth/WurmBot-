package net.ildar.wurm.bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import com.wurmonline.server.spells.Harden;

import com.wurmonline.client.comm.ServerConnectionListenerClass;
import com.wurmonline.client.comm.SimpleServerConnectionClass;
import com.wurmonline.client.game.PlayerObj;
import com.wurmonline.client.game.World;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.GroundItemData;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.client.renderer.gui.InventoryListComponent;
import com.wurmonline.client.renderer.gui.PaperDollInventory;
import com.wurmonline.client.renderer.gui.PaperDollSlot;
import com.wurmonline.client.renderer.gui.TargetWindow;
import com.wurmonline.client.game.SpellEffect;
import com.wurmonline.mesh.Tiles.Tile;
import com.wurmonline.shared.constants.PlayerAction;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;

@BotInfo(description =
        "Enchants selected group of items",
        abbreviation = "e")
class AreaAssistant {
    private static final int STEPS_IN_MOVE = 5;
    private int moveAheadDistance = 3;
    private int moveRightDistance = 3;
    private long stepTimeout = 1000L;
    private Bot bot;
    private int height = 0, width = 0;
    private int movedAhead = 0;
    private int movedToRight = 0;
    private int startX;
    private int startY;
    private int startDirection;
    private boolean turnedRight = false;
    private HashMap<Long, Long> corpseTimes = new HashMap<>();
    private HashSet<String> blacklistedCorpseNames = new HashSet<>();
    private HashMap<Long, Long> groomedCreatures = new HashMap<>();
    // creatures which were just queued to be groomed, cleared from groomedCreatures if groomingFailed
    private HashSet<Long> groomingQueued = new HashSet<>();
    private InventoryListComponent lumpHeatingInventory;

    AreaAssistant(Bot bot) {
        this.bot = bot;
        bot.registerInputHandler(InputKey.area, this::toggleAreaTour);
        bot.registerInputHandler(InputKey.area_speed, this::setAreaModeSpeed);
    }

    void areaNextPosition() throws InterruptedException {
        if (!areaTourActivated())
            return;
        recalculateBiases();
        if (this.movedAhead < 0 || this.movedAhead > this.height - 1 || this.movedToRight < 0 || this.movedToRight > this.width - 1) {
            Utils.consolePrint("Player leaved the area", new Object[0]);
            stopAreaTour();
            return;
        }
        turnPlayer();
        if (this.movedAhead < this.height - 1) {
            for (int tiles = 0; tiles < this.moveAheadDistance &&
                    this.movedAhead < this.height - 1; tiles++) {
                Utils.movePlayerBySteps(4.0F, 5, this.stepTimeout);
                this.movedAhead++;
            }
        } else if (this.movedToRight < this.width - 1) {
            if (this.turnedRight) {
                Utils.turnPlayer(-90.0F);
            } else {
                Utils.turnPlayer(90.0F);
            }
            Thread.sleep(300L);
            for (int tiles = 0; tiles < this.moveRightDistance &&
                    this.movedToRight < this.width - 1; tiles++) {
                Utils.movePlayerBySteps(4.0F, 5, this.stepTimeout);
                this.movedToRight++;
            }
            if (this.turnedRight) {
                Utils.turnPlayer(-90.0F);
            } else {
                Utils.turnPlayer(90.0F);
            }
            this.turnedRight = !this.turnedRight;
            this.movedAhead = 0;
        } else {
            stopAreaTour();
        }
        Utils.stabilizePlayer();
    }

    private void turnPlayer() {
        if (this.turnedRight) {
            Utils.turnPlayer(((this.startDirection + 2) % 4 * 90), 0.0F);
        } else {
            Utils.turnPlayer((this.startDirection * 90), 0.0F);
        }
    }

    private void recalculateBiases() {
        int x = WurmHelper.hud.getWorld().getPlayerCurrentTileX();
        int y = WurmHelper.hud.getWorld().getPlayerCurrentTileY();
        switch (this.startDirection) {
            case 1:
                this.movedAhead = x - this.startX;
                this.movedToRight = y - this.startY;
                break;
            case 2:
                this.movedAhead = y - this.startY;
                this.movedToRight = this.startX - x;
                break;
            case 3:
                this.movedAhead = this.startX - x;
                this.movedToRight = this.startY - y;
                break;
            default:
                this.movedAhead = this.startY - y;
                this.movedToRight = x - this.startX;
                break;
        }
        if (this.turnedRight)
            this.movedAhead = this.height - this.movedAhead - 1;
    }

    private void stopAreaTour() {
        Utils.showOnScreenMessage("Area tour is ended");
        this.height = 0;
        this.width = 0;
        this.movedAhead = 0;
        this.movedToRight = 0;
        this.turnedRight = false;
    }

    boolean areaTourActivated() {
        return (this.height != 0 && this.width != 0);
    }

    private void startAreaTour(int tilesForward, int tilesToRight) {
        this.height = tilesForward;
        this.width = tilesToRight;
        this.movedAhead = this.movedToRight = 0;
        this.startX = WurmHelper.hud.getWorld().getPlayerCurrentTileX();
        this.startY = WurmHelper.hud.getWorld().getPlayerCurrentTileY();
        this.turnedRight = false;
        Utils.stabilizePlayer();
        this.startDirection = Math.round(WurmHelper.hud.getWorld().getPlayerRotX() / 90.0F);
    }

    void setMoveAheadDistance(int moveAheadDistance) {
        this.moveAheadDistance = moveAheadDistance;
    }

    void setMoveRightDistance(int moveRightDistance) {
        this.moveRightDistance = moveRightDistance;
    }

    void toggleAreaTour(String[] input) {
        if (areaTourActivated()) {
            stopAreaTour();
        } else if (input != null && input.length == 2) {
            try {
                startAreaTour(Integer.parseInt(input[0]), Integer.parseInt(input[1]));
                Utils.consolePrint("Activated area mode for " + this.bot.getClass().getSimpleName(), new Object[0]);
            } catch (NumberFormatException e) {
                Utils.consolePrint("Wrong area size!", new Object[0]);
                this.bot.printInputKeyUsageString(InputKey.area);
            }
        } else {
            this.bot.printInputKeyUsageString(InputKey.area);
        }
    }

    private void setAreaModeSpeed(String[] input) {
        if (input == null || input.length != 1) {
            this.bot.printInputKeyUsageString(InputKey.area_speed);
            return;
        }
        try {
            float speed = Float.parseFloat(input[0]);
            if (speed < 0.0F) {
                Utils.consolePrint("Speed can not be negative", new Object[0]);
                return;
            }
            if (speed == 0.0F) {
                Utils.consolePrint("Speed can not be equal to 0", new Object[0]);
                return;
            }
            this.stepTimeout = (long)(1000.0F / speed);
            Utils.consolePrint(String.format("The speed for area mode was set to %.2f", new Object[] { Float.valueOf(speed) }), new Object[0]);
        } catch (NumberFormatException e) {
            Utils.consolePrint("Wrong speed value", new Object[0]);
        }
    }

    private enum InputKey implements Bot.InputKey {
        area("Toggle the area processing mode. ", "tiles_ahead tiles_to_the_right"),
        area_speed("Set the speed of moving for area mode. Default value is 1 second per tile.", "speed(float value)");

        private String description;

        private String usage;

        InputKey(String description, String usage) {
            this.description = description;
            this.usage = usage;
        }

        public String getName() {
            return name();
        }

        public String getDescription() {
            return this.description;
        }

        public String getUsage() {
            return this.usage;
        }
    }
}
