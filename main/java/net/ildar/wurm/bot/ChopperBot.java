package net.ildar.wurm.bot;

import com.wurmonline.client.comm.ServerConnectionListenerClass;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.GroundItemData;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.shared.constants.PlayerAction;
import java.util.ConcurrentModificationException;
import java.util.Map;
import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;

public class ChopperBot extends Bot {
    private static float distance = 4.0F;

    private AreaAssistant areaAssistant = new AreaAssistant(this);

    private float staminaThreshold;

    private int clicks;

    public ChopperBot() {
        registerInputHandler(InputKey.s, this::setStaminaThreshold);
        registerInputHandler(InputKey.d, this::setDistance);
        registerInputHandler(InputKey.c, this::setClickNumber);
        this.areaAssistant.setMoveAheadDistance(1);
        this.areaAssistant.setMoveRightDistance(1);
    }

    public void work() throws Exception {
        setStaminaThreshold(0.96F);
        setClicks(Utils.getMaxActionNumber());
        InventoryMetaItem hatchet = Utils.locateToolItem("hatchet");
        if (hatchet == null) {
            Utils.consolePrint("You don't have a hatchet!", new Object[0]);
            return;
        }
        long hatchetId = hatchet.getId();
        Utils.consolePrint(getClass().getSimpleName() + " will use " + hatchet.getDisplayName() + " to chop shriveled trees.", new Object[0]);
        Utils.consolePrint("QL:" + hatchet.getQuality() + " DMG:" + hatchet.getDamage(), new Object[0]);
        CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
        Object progressBar = Utils.getField(creationWindow, "progressBar");
        ServerConnectionListenerClass sscc = WurmHelper.hud.getWorld().getServerConnection().getServerConnectionListener();
        while (isActive()) {
            waitOnPause();
            float stamina = WurmHelper.hud.getWorld().getPlayer().getStamina();
            float damage = WurmHelper.hud.getWorld().getPlayer().getDamage();
            float progress = ((Float)Utils.getField(progressBar, "progress")).floatValue();
            if (stamina + damage > this.staminaThreshold && progress == 0.0F) {
                Map<Long, GroundItemCellRenderable> groundItems = (Map<Long, GroundItemCellRenderable>)Utils.getField(sscc, "groundItems");
                float x = WurmHelper.hud.getWorld().getPlayerPosX();
                float y = WurmHelper.hud.getWorld().getPlayerPosY();
                boolean didSomething = false;
                if (groundItems.size() > 0)
                    try {
                        for (Map.Entry<Long, GroundItemCellRenderable> entry : groundItems.entrySet()) {
                            GroundItemData groundItemData = (GroundItemData)Utils.getField(entry.getValue(), "item");
                            float itemX = groundItemData.getX();
                            float itemY = groundItemData.getY();
                            if (Math.sqrt(Math.pow((itemX - x), 2.0D) + Math.pow((itemY - y), 2.0D)) <= distance &&
                                    groundItemData.getName().contains("felled tree")) {
                                for (int i = 0; i < this.clicks; i++) {
                                    WurmHelper.hud.getWorld().getServerConnection().sendAction(hatchetId, new long[] { groundItemData.getId() }, PlayerAction.CHOP_UP);
                                }
                                didSomething = true;
                                break;
                            }
                        }
                    } catch (ConcurrentModificationException e) {
                        Utils.consolePrint("Got concurrent modification exception!", new Object[0]);
                    }
                if (!didSomething) {
                    this.areaAssistant.areaNextPosition();
                    continue;
                }
            }
            sleep(this.timeout);
        }
    }

    private void setDistance(String[] input) {
        if (input == null || input.length == 0) {
            printInputKeyUsageString(InputKey.d);
            return;
        }
        try {
            distance = Float.parseFloat(input[0]);
            Utils.consolePrint("New lookup distance is " + distance + " meters", new Object[0]);
        } catch (NumberFormatException e) {
            Utils.consolePrint("Wrong distance value!", new Object[0]);
        }
    }

    private void setStaminaThreshold(String[] input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(InputKey.s);
        } else {
            try {
                float threshold = Float.parseFloat(input[0]);
                setStaminaThreshold(threshold);
            } catch (Exception e) {
                Utils.consolePrint("Wrong threshold value!", new Object[0]);
            }
        }
    }

    private void setStaminaThreshold(float s) {
        this.staminaThreshold = s;
        Utils.consolePrint("Current threshold for stamina is " + this.staminaThreshold, new Object[0]);
    }

    private void setClickNumber(String[] input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(InputKey.c);
        } else {
            try {
                int clicks = Integer.parseInt(input[0]);
                setClicks(clicks);
            } catch (Exception e) {
                Utils.consolePrint("Wrong value!", new Object[0]);
            }
        }
    }

    private void setClicks(int clicks) {
        this.clicks = clicks;
        Utils.consolePrint(getClass().getSimpleName() + " will do " + clicks + " chops each time", new Object[0]);
    }

    private enum InputKey implements Bot.InputKey {
        s("Set the stamina threshold. Player will not do any actions if his stamina is lower than specified threshold", "threshold(float value between 0 and 1)"),
        d("Set the distance the bot should look around player in search for a felled tree", "distance(in meters)"),
        c("Set the amount of chops the bot will do each time", "c(integer value)");

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
