package net.ildar.wurm.bot;

import com.wurmonline.client.comm.ServerConnectionListenerClass;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.client.renderer.gui.PaperDollInventory;
import com.wurmonline.client.renderer.gui.PaperDollSlot;
import com.wurmonline.shared.constants.PlayerAction;
import java.util.Map;
import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;

public class ArcherBot extends Bot {
    private static boolean stringBreaks;

    private float staminaThreshold;

    private InventoryMetaItem bow;

    public ArcherBot() {
        registerInputHandler(InputKey.s, this::setStaminaThreshold);
        registerInputHandler(InputKey.string, input -> stringTheBow());

        // Help / command list
        registerInputHandler(InputKey.help, _in -> printArcherHelp());
    }

    /**
     * Prints a concise "bot ar help" list to the console.
     */
    private void printArcherHelp() {
        Utils.consolePrint("==== ArcherBot commands ====");
        Utils.consolePrint("Usage: bot ar <command> [args]");
        Utils.consolePrint("");

        InputKey[] keys = new InputKey[]{
                InputKey.s,
                InputKey.string,
                InputKey.help
        };

        for (InputKey k : keys) {
            if (k == null) continue;

            String usage = k.getUsage();
            String usageSuffix = (usage == null || usage.trim().isEmpty()) ? "" : " " + usage.trim();

            Utils.consolePrint(" - %s%s : %s", k.getName(), usageSuffix, k.getDescription());
        }

        Utils.consolePrint("");
    }

    public void work() throws Exception {
        setStaminaThreshold(0.9F);
        PaperDollInventory pdi = (PaperDollInventory)Utils.getField(WurmHelper.hud, "paperdollInventory");
        Map<Long, PaperDollSlot> frameList = (Map<Long, PaperDollSlot>)Utils.getField(pdi, "frameList");
        for (Map.Entry<Long, PaperDollSlot> frame : frameList.entrySet()) {
            PaperDollSlot slot = frame.getValue();
            if (slot != null && slot.getEquippedItem() != null &&
                    slot.getEquipmentSlot() == 1) {
                this.bow = slot.getEquippedItem().getItem();
                Utils.consolePrint(getClass().getSimpleName() + " will use " + this.bow.getDisplayName() + " with QL:" + this.bow.getQuality() + " DMG:" + this.bow.getDamage(), new Object[0]);
            }
        }
        if (this.bow == null) {
            Utils.consolePrint("Equip the bow first!", new Object[0]);
            deactivate();
            return;
        }
        PickableUnit pickableUnit = (PickableUnit)Utils.getField(WurmHelper.hud.getSelectBar(), "selectedUnit");
        if (pickableUnit == null) {
            Utils.consolePrint("Select mob!", new Object[0]);
            deactivate();
            return;
        }
        Utils.consolePrint(getClass().getSimpleName() + " will shoot at " + pickableUnit.getHoverName(), new Object[0]);
        long mobId = pickableUnit.getId();
        boolean isArcheryTarget = pickableUnit.getHoverName().contains("archery target");
        int maxActions = Utils.getMaxActionNumber();
        CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
        Object progressBar = Utils.getField(creationWindow, "progressBar");
        registerEventProcessors();
        while (isActive()) {
            waitOnPause();
            float stamina = WurmHelper.hud.getWorld().getPlayer().getStamina();
            float damage = WurmHelper.hud.getWorld().getPlayer().getDamage();
            float progress = ((Float)Utils.getField(progressBar, "progress")).floatValue();
            if (stamina + damage > this.staminaThreshold && creationWindow.getActionInUse() == 0 && progress == 0.0F) {
                if (stringBreaks) {
                    InventoryMetaItem bowstring = Utils.getInventoryItem("bow string");
                    if (bowstring != null)
                        WurmHelper.hud.getWorld().getServerConnection().sendAction(bowstring.getId(), new long[] { this.bow
                                .getId() }, new PlayerAction("", (short)132, 65535));
                }
                for (int i = 0; i < maxActions; i++) {
                    WurmHelper.hud.getWorld().getServerConnection().sendAction(this.bow.getId(), new long[] { mobId }, !isArcheryTarget ? PlayerAction.SHOOT : new PlayerAction("", (short)134, 65535));
                }
                ServerConnectionListenerClass sscc = WurmHelper.hud.getWorld().getServerConnection().getServerConnectionListener();
                Map<Long, CreatureCellRenderable> creatures = (Map<Long, CreatureCellRenderable>)Utils.getField(sscc, "creatures");
                boolean mobAlive = false;
                if (creatures != null && !isArcheryTarget)
                    for (Map.Entry<Long, CreatureCellRenderable> entry : creatures.entrySet()) {
                        if (((CreatureCellRenderable)entry.getValue()).getId() == mobId)
                            mobAlive = true;
                    }
                if (!mobAlive && !isArcheryTarget) {
                    Utils.consolePrint("Mob dead or too far away!", new Object[0]);
                    Utils.showOnScreenMessage("Deactivating archerbot!");
                    deactivate();
                }
            }
            sleep(this.timeout);
        }
    }

    private void registerEventProcessors() {
        registerEventProcessor(message -> Boolean.valueOf(message.contains("You string the ")), () -> stringBreaks = false);
        registerEventProcessor(message -> Boolean.valueOf(message.contains("The string breaks!")), () -> stringBreaks = true);
        registerMessageProcessor(":Combat", message -> Boolean.valueOf(message.contains("The string breaks!")), () -> stringBreaks = true);
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

    private void stringTheBow() {
        Utils.consolePrint(getClass().getSimpleName() + " will try to string the bow.", new Object[0]);
        stringBreaks = true;
    }

    private enum InputKey implements Bot.InputKey {
        s("Set the stamina threshold. Player will not do any actions if his stamina is lower than specified threshold", "threshold(float value between 0 and 1)"),
        string("String the current bow with a string", ""),
        help("Show this help in the console", "");

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
