package net.ildar.wurm.bot;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.gui.*;
import com.wurmonline.shared.constants.PlayerAction;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.Utils;
import net.ildar.wurm.annotations.BotInfo;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@BotInfo(description =
        "Automatically does crafting operations using items from crafting window. " +
                "New crafting operations are not starting until an action queue becomes empty. This behaviour can be disabled. ",
        abbreviation = "c")
public class CrafterBot extends Bot {
    private float staminaThreshold;
    private boolean repairInstrument = true;
    private String targetName;
    private String sourceName;
    private Comparator<InventoryMetaItem> weightComparator = Comparator.comparingDouble(InventoryMetaItem::getWeight);
    private int targetX;
    private int targetY;
    private int sourceX;
    private int sourceY;
    private boolean noSort = true;
    private boolean combineTargets;
    private boolean combineSources;
    private long combineTimeout;
    private boolean craftUnfinishedItemMode;
    private boolean withoutActionsInUse;
    private long lastClick;
    private boolean singleSourceItemMode;

    private volatile boolean repairInitiated;
    private long pendingRepairItemId = 0L;
    private int pendingRepairTries = 0;
    private long lastPendingRepairTryMs = 0L;
    private static final int MAX_REPAIR_TRIES = 30;
    private static final long REPAIR_RETRY_DELAY_MS = 1000L;

    private static final float REPAIR_DAMAGE_THRESHOLD = 1.0f;

    // If a fixed-point pick produces a "ghost" entry, a second read usually resolves it.
    private static final int FIXED_POINT_EMPTY_SLOT_RETRIES = 2;

    public CrafterBot() {
        registerInputHandler(CrafterBot.InputKey.r, input -> toggleRepairInstrument());
        registerInputHandler(CrafterBot.InputKey.st, this::setTargetName);
        registerInputHandler(CrafterBot.InputKey.stxy, input -> setTargetXY());
        registerInputHandler(CrafterBot.InputKey.ss, this::setSourceName);
        registerInputHandler(CrafterBot.InputKey.ssxy, input -> setSourceXY());
        registerInputHandler(CrafterBot.InputKey.nosort, input -> toggleSorting());
        registerInputHandler(CrafterBot.InputKey.ct, input -> toggleTargetsCombining());
        registerInputHandler(CrafterBot.InputKey.cs, input -> toggleSourcesCombining());
        registerInputHandler(CrafterBot.InputKey.ctimeout, this::setCombineTimeout);
        registerInputHandler(CrafterBot.InputKey.s, this::setStaminaThreshold);
        registerInputHandler(CrafterBot.InputKey.u, input -> toggleUnfinishedMode());
        registerInputHandler(CrafterBot.InputKey.ssid, this::addSourceByItemId);
        registerInputHandler(CrafterBot.InputKey.an, this::setActionNumber);
        registerInputHandler(CrafterBot.InputKey.noan, input -> toggleActionNumberChecks());
        registerInputHandler(CrafterBot.InputKey.s1s, input -> toggleSingleSourceItemMode());

        registerInputHandler(CrafterBot.InputKey.help, _in -> printCrafterHelp());
    }

    /**
     * Prints a concise "bot c help" list to the console.
     */
    private void printCrafterHelp() {
        Utils.consolePrint("==== CrafterBot commands ====");
        Utils.consolePrint("Usage: bot c <command> [args]");
        Utils.consolePrint("");

        // Keep order explicit (so output is stable)
        InputKey[] keys = new InputKey[]{
                InputKey.r,
                InputKey.st,
                InputKey.stxy,
                InputKey.ss,
                InputKey.ssxy,
                InputKey.nosort,
                InputKey.cs,
                InputKey.ct,
                InputKey.ctimeout,
                InputKey.s,
                InputKey.u,
                InputKey.ssid,
                InputKey.an,
                InputKey.noan,
                InputKey.s1s,
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

    @Override
    @SuppressWarnings("ConstantConditions")
    public void work() throws Exception{
        setStaminaThreshold(0.96f);
        long lastSourceCombineTime = 0;
        long lastTargetCombineTime = 0;

        CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
        Method sendCreateAction = ReflectionUtil.getMethod(CreationWindow.class, "sendCreateAction");
        sendCreateAction.setAccessible(true);
        Method requestCreationList = ReflectionUtil.getMethod(creationWindow.getClass(), "requestCreationList");
        requestCreationList.setAccessible(true);
        Object progressBar = Utils.getField(creationWindow, "progressBar");
        CreationFrame source = Utils.getField(creationWindow, "source");
        CreationFrame target = Utils.getField(creationWindow, "target");
        registerEventProcessors();

        while (isActive()) {
            waitOnPause();
            float stamina = WurmHelper.hud.getWorld().getPlayer().getStamina();
            float damage = WurmHelper.hud.getWorld().getPlayer().getDamage();
            float progress = Utils.getField(progressBar, "progress");

            if (repairInstrument) {
                InventoryMetaItem srcItem = getSourceSlotInventoryItem(source);
                if (srcItem != null && srcItem.getDamage() > REPAIR_DAMAGE_THRESHOLD) {
                    long srcId = srcItem.getId();

                    if (pendingRepairItemId != srcId) {
                        pendingRepairItemId = srcId;
                        pendingRepairTries = 0;
                        repairInitiated = false;
                        lastPendingRepairTryMs = 0L;
                    }

                    long now = System.currentTimeMillis();
                    if (!repairInitiated
                            && pendingRepairTries < MAX_REPAIR_TRIES
                            && (now - lastPendingRepairTryMs) >= REPAIR_RETRY_DELAY_MS) {
                        WurmHelper.hud.sendAction(PlayerAction.REPAIR, pendingRepairItemId);
                        lastPendingRepairTryMs = now;
                        pendingRepairTries++;
                    }

                    if (repairInitiated) {
                        pendingRepairItemId = 0L;
                        pendingRepairTries = 0;
                        lastPendingRepairTryMs = 0L;
                    }
                } else {
                    // Nothing to repair (or can't read damage); reset state so we don't loop-repair “nothing”.
                    pendingRepairItemId = 0L;
                    pendingRepairTries = 0;
                    lastPendingRepairTryMs = 0L;
                    repairInitiated = false;
                }
            }

            if (craftUnfinishedItemMode) {
                WurmTreeList<CreationItemTreeLisItem> unfinishedItemList = Utils.getField(creationWindow, "unfinishedItemList");
                if (unfinishedItemList != null) {
                    List lines = Utils.getField(unfinishedItemList, "lines");
                    if (lines != null && lines.size() > 0) {
                        targetName = null;
                        //noinspection ForLoopReplaceableByForEach
                        for (int i = 0; i < lines.size(); i++) {
                            CreationItemTreeLisItem listItem = Utils.getField(lines.get(i), "item");
                            String chance = Utils.getField(listItem, "chance");
                            if (chance != null && !chance.equals("") && !chance.contains("%")) {
                                targetName = Utils.getField(listItem, "name");
                                break;
                            }
                        }
                    } else {
                        requestCreationList.invoke(creationWindow);
                    }
                }
            }
            if (targetName != null && targetName.length() > 0) {
                List<InventoryMetaItem> targetItems = Utils.getInventoryItems(targetName).stream().filter(item -> item.getBaseName().equals(targetName)).collect(Collectors.toList());
                if (!noSort)
                    targetItems.sort(weightComparator);
                Utils.setField(target, "itemList", targetItems);
                if (targetItems.size() > 0)
                    target.setTexture(targetItems.get(0));
            }
            if (sourceName != null && sourceName.length() > 0) {
                List<InventoryMetaItem> sourceItems = Utils.getInventoryItems(sourceName).stream().filter(item -> item.getBaseName().equals(sourceName)).collect(Collectors.toList());
                if (!noSort)
                    sourceItems.sort(weightComparator);
                if (singleSourceItemMode && sourceItems != null && sourceItems.size() > 0) {
                    List<InventoryMetaItem> singleSourceItemList = new ArrayList<>();
                    singleSourceItemList.add(sourceItems.get(0));
                    Utils.setField(source, "itemList", singleSourceItemList);
                } else {
                    Utils.setField(source, "itemList", sourceItems);
                }
                if (sourceItems.size() > 0)
                    source.setTexture(sourceItems.get(0));
            }

            if (targetX != 0 && targetY != 0) {
                int tries = isCreationFrameEmpty(target) ? FIXED_POINT_EMPTY_SLOT_RETRIES : 1;
                setCreationFrameItemsFromPoint(target, targetX, targetY, tries);
            }

            if (sourceX != 0 && sourceY != 0) {
                int tries = isCreationFrameEmpty(source) ? FIXED_POINT_EMPTY_SLOT_RETRIES : 1;
                setCreationFrameItemsFromPoint(source, sourceX, sourceY, tries);
            }

            if (combineTargets && (Math.abs(lastTargetCombineTime - System.currentTimeMillis()) > combineTimeout)) {
                lastTargetCombineTime = System.currentTimeMillis();
                List<InventoryMetaItem> targetItems = Utils.getField(target, "itemList");
                if (targetItems != null && targetItems.size() > 1) {
                    long[] targets = Utils.getItemIds(targetItems);
                    creationWindow.sendCombineAction(targets[0], targets, target);
                    requestCreationList.invoke(creationWindow);
                }
            }

            if (combineSources && (Math.abs(lastSourceCombineTime - System.currentTimeMillis()) > combineTimeout)) {
                lastSourceCombineTime = System.currentTimeMillis();
                List<InventoryMetaItem> sourceItems = Utils.getField(source, "itemList");
                if (sourceItems != null && sourceItems.size() > 1) {
                    long[] sources = Utils.getItemIds(sourceItems);
                    creationWindow.sendCombineAction(sources[0], sources, source);
                    requestCreationList.invoke(creationWindow);
                }
            }

            if (source != null && target != null && (stamina+damage) > staminaThreshold && (creationWindow.getActionInUse() == 0 || withoutActionsInUse) && progress == 0f) {
                sendCreateAction.invoke(creationWindow);
            }
            if (source != null && target != null
                    && (stamina+damage) > staminaThreshold
                    && Math.abs(lastClick - System.currentTimeMillis()) > 20000){
                requestCreationList.invoke(creationWindow);
                while (creationWindow.getActionInUse() > 0)
                    creationWindow.decreaseActionInUse();
                lastClick = System.currentTimeMillis();
            }

            sleep(timeout);
        }
    }

    private boolean isCreationFrameEmpty(CreationFrame frame) {
        if (frame == null) return true;

        try {
            @SuppressWarnings("unchecked")
            List<InventoryMetaItem> items = (List<InventoryMetaItem>) Utils.getField(frame, "itemList");
            if (items != null && !items.isEmpty() && items.get(0) != null)
                return false;
        } catch (Exception ignored) {
        }

        // Ground tools placed in slot can be represented separately
        try {
            Object gci = Utils.getField(frame, "groundCreationItem");
            if (gci != null) return false;
        } catch (Exception ignored) {
        }

        return true;
    }

    private void setCreationFrameItemsFromPoint(CreationFrame frame, int x, int y, int tries) {
        if (frame == null) return;
        if (tries < 1) tries = 1;

        for (int i = 0; i < tries; i++) {
            List<InventoryMetaItem> items = Utils.getInventoryItemsAtPoint(x, y);
            if (items != null && items.size() > 0) {
                try {
                    Utils.setField(frame, "itemList", items);
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    private InventoryMetaItem getSourceSlotInventoryItem(CreationFrame source) {
        if (source == null) return null;

        try {
            @SuppressWarnings("unchecked")
            List<InventoryMetaItem> sourceItems = (List<InventoryMetaItem>) Utils.getField(source, "itemList");
            if (sourceItems != null && !sourceItems.isEmpty())
                return sourceItems.get(0);
        } catch (Exception ignored) {
        }

        return null;
    }

    private long getSourceSlotItemId(CreationFrame source) {
        if (source == null) return 0L;

        // Works for inventory items / when bot populated the slot
        try {
            @SuppressWarnings("unchecked")
            List<InventoryMetaItem> sourceItems = (List<InventoryMetaItem>) Utils.getField(source, "itemList");
            if (sourceItems != null && !sourceItems.isEmpty() && sourceItems.get(0) != null)
                return sourceItems.get(0).getId();
        } catch (Exception ignored) {
        }

        // Works for ground tools placed in the slot
        try {
            Object gci = Utils.getField(source, "groundCreationItem");
            if (gci == null) return 0L;

            for (String mName : new String[]{"getId", "getItemId", "getSourceId", "getTargetId"}) {
                try {
                    Method m = gci.getClass().getMethod(mName);
                    Object idObj = m.invoke(gci);
                    if (idObj instanceof Number)
                        return ((Number) idObj).longValue();
                } catch (Exception ignoredInner) {
                }
            }

            for (String fName : new String[]{"id", "itemId", "sourceId", "targetId"}) {
                try {
                    Field f = gci.getClass().getDeclaredField(fName);
                    f.setAccessible(true);
                    Object idObj = f.get(gci);
                    if (idObj instanceof Number)
                        return ((Number) idObj).longValue();
                } catch (Exception ignoredInner) {
                }
            }
        } catch (Exception ignored) {
        }

        return 0L;
    }

    private void registerEventProcessors() {
        registerEventProcessor(message -> message.contains("You create")
                || message.contains("you will start creating")
                || message.contains("You attach")
                || message.contains("you will start continuing"), () -> lastClick = System.currentTimeMillis());

        registerEventProcessor(message -> message.contains("You repair")
                || message.contains("You start repairing")
                || message.contains("doesn't need repairing")
                || message.contains("you will start repairing"), () -> repairInitiated = true);
    }

    private void toggleActionNumberChecks() {
        withoutActionsInUse = !withoutActionsInUse;
        if (!withoutActionsInUse) {
            Utils.consolePrint(this.getClass().getSimpleName() + " will NOT check action queue");
        } else {
            Utils.consolePrint(this.getClass().getSimpleName() + " will check action queue");

        }
    }

    private void toggleSingleSourceItemMode() {
        singleSourceItemMode = !singleSourceItemMode;
        Utils.consolePrint("Single source item mode is " + (singleSourceItemMode?"on":"off"));
    }

    private void setActionNumber(String input[]) {
        if(input == null || input.length != 1) {
            printInputKeyUsageString(CrafterBot.InputKey.an);
            return;
        }

        try {
            int num = Integer.parseInt(input[0]);
            CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
            Utils.setField(creationWindow, "selectedActions", num);
        } catch (Exception e) {
            Utils.consolePrint("Can't set an action number");
        }
    }

    private void addSourceByItemId(String input[]) {
        if(input == null || input.length != 1) {
            printInputKeyUsageString(CrafterBot.InputKey.ssid);
            return;
        }
        try {
            long id = Long.parseLong(input[0]);
            InventoryListComponent ilc = WurmHelper.hud.getInventoryWindow().getInventoryListComponent();
            List <InventoryMetaItem> allItems = Utils.getSelectedItems(ilc, true, true);
            @SuppressWarnings("ConstantConditions")
            InventoryMetaItem sourceItem = allItems.stream().filter(item->item.getId() == id).findAny().get();
            CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
            CreationFrame source = Utils.getField(creationWindow, "source");
            List<InventoryMetaItem> newSourceList = new ArrayList<>();
            newSourceList.add(sourceItem);
            Utils.setField(source, "itemList", newSourceList);
            Method requestCreationList = ReflectionUtil.getMethod(creationWindow.getClass(), "requestCreationList");
            requestCreationList.setAccessible(true);
            requestCreationList.invoke(creationWindow);

        } catch (Exception e) {
            Utils.consolePrint("Can't set new source item with provided id");
        }
    }

    private void toggleUnfinishedMode() {
        if (!craftUnfinishedItemMode) {
            targetName = null;
            targetX = targetY = 0;
            craftUnfinishedItemMode = true;
            Utils.consolePrint("The unfinished item crafting mode is on!");
        } else {
            craftUnfinishedItemMode = false;

            // IMPORTANT: clear any auto-target so we return to "manual / standard" crafting behavior.
            targetName = null;
            targetX = targetY = 0;

            Utils.consolePrint("The unfinished item crafting mode is off!");
        }
    }

    private void setStaminaThreshold(String input[]) {
        if (input == null || input.length != 1)
            printInputKeyUsageString(CrafterBot.InputKey.s);
        else {
            try {
                float threshold = Float.parseFloat(input[0]);
                setStaminaThreshold(threshold);
            } catch (Exception e) {
                Utils.consolePrint("Wrong threshold value!");
            }
        }
    }

    private void setStaminaThreshold(float s) {
        staminaThreshold = s;
        Utils.consolePrint("Current threshold for stamina is " + staminaThreshold);
    }

    private void setCombineTimeout(String input[]) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(CrafterBot.InputKey.ctimeout);
            return;
        }
        try {
            setCombineTimeout(Long.parseLong(input[0]));
        } catch (NumberFormatException e) {
            Utils.consolePrint("Wrong timeout value");
        }
    }

    private void setCombineTimeout(long timeout) {
        this.combineTimeout = timeout;
        Utils.consolePrint("Timeout for item combining is " + combineTimeout);
    }

    private void toggleSourcesCombining() {
        combineSources = !combineSources;
        if (combineSources) {
            Utils.consolePrint("Source combining is on!");
            if (combineTimeout == 0)
                setCombineTimeout(10000);
        } else
            Utils.consolePrint("Source combining is off!");
    }

    private void toggleTargetsCombining() {
        combineTargets = !combineTargets;
        if (combineTargets) {
            Utils.consolePrint("Target combining is on!");
            if (combineTimeout == 0)
                setCombineTimeout(10000);
        } else
            Utils.consolePrint("Target combining is off!");
    }

    private void toggleSorting() {
        noSort = !noSort;
        if (noSort)
            Utils.consolePrint(this.getClass().getSimpleName() + " will NOT sort the targets and sources by weight");
        else
            Utils.consolePrint(this.getClass().getSimpleName() + " will sort the targets and sources by weight");
    }

    private void setTargetName(String input[]) {
        if(input == null || input.length == 0) {
            printInputKeyUsageString(CrafterBot.InputKey.st);
            return;
        }
        StringBuilder target = new StringBuilder(input[0]);
        for (int i = 1; i < input.length; i++)
            target.append(" ").append(input[i]);
        setTargetName(target.toString());
    }

    private void setSourceName(String input[]) {
        if(input == null || input.length == 0) {
            printInputKeyUsageString(CrafterBot.InputKey.ss);
            return;
        }
        StringBuilder source = new StringBuilder(input[0]);
        for (int i = 1; i < input.length; i++)
            source.append(" ").append(input[i]);
        setSourceName(source.toString());
    }

    private void toggleRepairInstrument(){
        repairInstrument = !repairInstrument;
        if (repairInstrument)
            Utils.consolePrint("Instrument auto repairing is on!");
        else
            Utils.consolePrint("Instrument auto repairing is off!");
    }

    private void setTargetName(String t) {
        if (t != null && t.length() > 0) {
            Utils.consolePrint("New target item name is - " + t);
            targetName = t;
        } else
            Utils.consolePrint("Can't set empty target item name");
    }

    private void setSourceName(String t) {
        if (t != null && t.length() > 0) {
            Utils.consolePrint("New source item name is - " + t);
            sourceName = t;
        } else
            Utils.consolePrint("Can't set empty source item name");
    }

    private void setTargetXY() {
        targetX = WurmHelper.hud.getWorld().getClient().getXMouse();
        targetY = WurmHelper.hud.getWorld().getClient().getYMouse();
        targetName = null;
        Utils.consolePrint("The target was set to X - " + targetX + " Y - " + targetY);
    }

    private void setSourceXY() {
        sourceX = WurmHelper.hud.getWorld().getClient().getXMouse();
        sourceY = WurmHelper.hud.getWorld().getClient().getYMouse();
        sourceName = null;
        Utils.consolePrint("The source was set to X - " + sourceX + " Y - " + sourceY);
    }

    private enum InputKey implements Bot.InputKey {
        r("Toggle the source item repairing(on the left side of crafting window). " +
                "Usually it is an instrument. When the source item gets 10% damage player will repair it automatically", ""),
        st("Set the target item name. " + CrafterBot.class.getSimpleName()+ " will place item with provided name from your inventory to the target slot(on the right side of crafting window)",
                "target_name"),
        stxy("Set the target item fixed point. " + CrafterBot.class.getSimpleName()+ " will place item from that fixed point of screen to the target item slot(on the right side of crafting window)", ""),
        ss("Set the source item name. " + CrafterBot.class.getSimpleName()+ " will place item with provided name from your inventory to the source slot(on the left side of crafting window)",
                "source_name"),
        ssxy("Set the source item fixed point. " + CrafterBot.class.getSimpleName()+ " will place item from that fixed point of screen to the source item slot(on the left side of crafting window)", ""),
        nosort("Sorting of source and target items is enabled by default. This key toggles sorting on and off", ""),
        cs("Combine source items(on the left side of crafting window)", ""),
        ct("Combine target items(on the right side of crafting window)", ""),
        ctimeout("Set the timeout for item combining", "timeout(in milliseconds)"),
        s("Set the stamina threshold. Player will not do any actions if his stamina is lower than specified threshold",
                "threshold(float value between 0 and 1)"),
        u("Toggle the special mode in which " + CrafterBot.class.getSimpleName() + " will place an item to the target item slot which is at the top of \"Needed items\" list", ""),
        ssid("Set an item with provided id to the source slot(on the left side of crafting window)", "id"),
        an("Set an action number. The number of crafting operations the player will do on each click on continue/create button", "number"),
        noan("Toggles the check for action queue state before the start of each crafting operation. " +
                "By default " + CrafterBot.class.getSimpleName() + " will check action queue and start crafting operations only when it is empty", ""),
        s1s("Toggles the setting of single item to source slot of crafting window", ""),
        help("Show this help in the console", "");

        private String description;
        private String usage;
        InputKey(String description, String usage) {
            this.description = description;
            this.usage = usage;
        }

        @Override
        public String getName() {
            return name();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getUsage() {
            return usage;
        }
    }
}