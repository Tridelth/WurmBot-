package net.ildar.wurm;

import com.wurmonline.client.comm.ServerConnectionListenerClass;
import com.wurmonline.client.game.PlayerObj;
import com.wurmonline.client.game.SkillLogicSet;
import com.wurmonline.client.game.World;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.CreatureData;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.client.renderer.gui.InventoryListComponent;
import com.wurmonline.client.renderer.gui.MindLogicCalculator;
import com.wurmonline.client.renderer.gui.PaperDollInventory;
import com.wurmonline.client.renderer.gui.PaperDollSlot;
import com.wurmonline.client.renderer.gui.WurmComponent;
import com.wurmonline.client.renderer.gui.WurmTreeList;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

public class Utils {
    public static ReentrantLock serverCallLock = new ReentrantLock();

    public static Queue<String> consoleMessages = new ConcurrentLinkedQueue<>();

    private static final float maxActionSqDistance = 25.0F;

    public static void consolePrint(String fmt, Object... args) {
        if (fmt == null)
            return;
        fmt = (args.length == 0) ? fmt : String.format(fmt, args);
        for (String line : fmt.split("\n"))
            consoleMessages.add(line);
    }

    public static void showOnScreenMessage(String message) {
        showOnScreenMessage(message, 1.0F, 1.0F, 1.0F);
    }

    public static void showOnScreenMessage(String message, float r, float g, float b) {
        WurmHelper.hud.addOnscreenMessage(message, r, g, b, (byte)1);
        consolePrint(message, new Object[0]);
    }

    public static <Cls, Ret> Ret getField(Cls what, String field) throws IllegalAccessException, NoSuchFieldException {
        return (Ret)ReflectionUtil.getPrivateField(what, ReflectionUtil.getField(what.getClass(), field));
    }

    public static <Cls, Field> void setField(Cls what, String field, Field value) throws IllegalAccessException, NoSuchFieldException {
        ReflectionUtil.setPrivateField(what, ReflectionUtil.getField(what.getClass(), field), value);
    }

    public static void turnPlayer(float dxRot) {
        try {
            PlayerObj ply = WurmHelper.hud.getWorld().getPlayer();
            float xRot = ((Float)getField(ply, "xRotUsed")).floatValue();
            xRot = (xRot + dxRot) % 360.0F;
            if (xRot < 0.0F)
                xRot = (xRot + 360.0F) % 360.0F;
            setField(ply, "xRotUsed", Float.valueOf(xRot));
        } catch (Exception e) {
            consolePrint("Unexpected error while turning - " + e.getMessage(), new Object[0]);
        }
    }

    public static void turnPlayer(float xRot, float yRot) {
        try {
            PlayerObj ply = WurmHelper.hud.getWorld().getPlayer();
            if (!Float.isNaN(xRot))
                setField(ply, "xRotUsed", Float.valueOf(xRot));
            if (!Float.isNaN(yRot))
                setField(ply, "yRotUsed", Float.valueOf(yRot));
        } catch (Exception e) {
            consolePrint("Unexpected error while turning - " + e.getMessage(), new Object[0]);
        }
    }

    public static void movePlayer(float d) {
        try {
            float x = WurmHelper.hud.getWorld().getPlayerPosX();
            float y = WurmHelper.hud.getWorld().getPlayerPosY();
            float xr = ((Float)getField(WurmHelper.hud.getWorld().getPlayer(), "xRotUsed")).floatValue();
            float dx = (float)(d * Math.sin(xr / 180.0D * Math.PI));
            float dy = (float)(-d * Math.cos(xr / 180.0D * Math.PI));
            movePlayer(x + dx, y + dy);
        } catch (Exception e) {
            consolePrint("Unexpected error while moving - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
        }
    }

    public static void movePlayerBySteps(float d, int steps, long duration) throws InterruptedException {
        try {
            float x = WurmHelper.hud.getWorld().getPlayerPosX();
            float y = WurmHelper.hud.getWorld().getPlayerPosY();
            float xr = ((Float)getField(WurmHelper.hud.getWorld().getPlayer(), "xRotUsed")).floatValue();
            float dx = (float)(d * Math.sin(xr / 180.0D * Math.PI));
            float dy = (float)(-d * Math.cos(xr / 180.0D * Math.PI));
            movePlayerBySteps(x + dx, y + dy, steps, duration);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            consolePrint("Unexpected error while moving - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
        }
    }

    public static void movePlayerBySteps(float x, float y, int steps, long duration) throws InterruptedException {
        float curX = WurmHelper.hud.getWorld().getPlayerPosX();
        float curY = WurmHelper.hud.getWorld().getPlayerPosY();
        float xStep = (x - curX) / steps;
        float yStep = (y - curY) / steps;
        for (int stepIndex = 0; stepIndex < steps; stepIndex++) {
            movePlayer(curX + xStep * (stepIndex + 1), curY + yStep * (stepIndex + 1));
            Thread.sleep(duration / steps);
        }
    }

    public static void movePlayer(float x, float y) {
        try {
            float z;
            World world = WurmHelper.hud.getWorld();
            PlayerObj ply = world.getPlayer();
            setField(ply, "xPosUsed", Float.valueOf(x));
            setField(ply, "yPosUsed", Float.valueOf(y));
            if (ply.getLayer() >= 0) {
                z = Math.max(-1.0F, world.getNearTerrainBuffer().getInterpolatedHeight(x, y));
            } else {
                z = Math.min(-1.0F, world.getCaveBuffer().getInterpolatedFloor(x, y));
            }
            setField(ply, "hPosUsed", Float.valueOf(z));
        } catch (Exception e) {
            consolePrint("Unexpected error while moving - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
        }
    }

    public static void stabilizePlayer() {
        moveToCenter();
        stabilizeLook();
    }

    public static void stabilizeLook() {
        try {
            float xRot = ((Float)getField(WurmHelper.hud.getWorld().getPlayer(), "xRotUsed")).floatValue();
            xRot = (Math.round(xRot / 90.0F) * 90);
            setField(WurmHelper.hud.getWorld().getPlayer(), "xRotUsed", Float.valueOf(xRot));
            setField(WurmHelper.hud.getWorld().getPlayer(), "yRotUsed", Float.valueOf(0.0F));
        } catch (Exception e) {
            consolePrint("Unexpected error while turning - " + e.getMessage(), new Object[0]);
        }
    }

    public static void moveToCenter() {
        try {
            float x = WurmHelper.hud.getWorld().getPlayerPosX();
            float y = WurmHelper.hud.getWorld().getPlayerPosY();
            x = (float)(Math.floor(x / 4.0D) * 4.0D + 2.0D);
            y = (float)(Math.floor(y / 4.0D) * 4.0D + 2.0D);
            setField(WurmHelper.hud.getWorld().getPlayer(), "xPosUsed", Float.valueOf(x));
            setField(WurmHelper.hud.getWorld().getPlayer(), "yPosUsed", Float.valueOf(y));
        } catch (Exception e) {
            consolePrint("Unexpected error while moving - " + e.getMessage(), new Object[0]);
        }
    }

    public static void moveToNearestCorner() {
        try {
            float x = WurmHelper.hud.getWorld().getPlayerPosX();
            float y = WurmHelper.hud.getWorld().getPlayerPosY();
            x = (Math.round(x / 4.0F) * 4);
            y = (Math.round(y / 4.0F) * 4);
            setField(WurmHelper.hud.getWorld().getPlayer(), "xPosUsed", Float.valueOf(x));
            setField(WurmHelper.hud.getWorld().getPlayer(), "yPosUsed", Float.valueOf(y));
        } catch (Exception e) {
            consolePrint("Error on moving to the corner", new Object[0]);
        }
    }

    public static float itemFavor(InventoryMetaItem item, float c) {
        float quality = item.getQuality() * (1.0F - item.getDamage() / 100.0F);
        return quality * quality / 500.0F * c;
    }

    private static Object getInventoryRootNode(InventoryListComponent ilc) throws NoSuchFieldException, IllegalAccessException {
        WurmTreeList wtl = getField(ilc, "itemList");
        return getField(wtl, "rootNode");
    }

    private static List<Object> getNodeChildren(Object node) throws NoSuchFieldException, IllegalAccessException {
        return new ArrayList(getField(node, "children"));
    }

    public static List<InventoryMetaItem> getSelectedItems() {
        return getSelectedItems(false, true);
    }

    public static List<InventoryMetaItem> getSelectedItems(boolean getAll, boolean recursive) {
        InventoryListComponent ilc = WurmHelper.hud.getInventoryWindow().getInventoryListComponent();
        List<InventoryMetaItem> selItems = new ArrayList<>();
        try {
            Object rootNode = getInventoryRootNode(ilc);
            List<Object> lines = getNodeChildren(rootNode);
            int lineNum = 1;
            int forEachIdx = 0;
            for (Object line : lines) {
                Object item = getField(line, "item");
                String itemName = getField(item, "itemName");
                if (itemName.equals("inventory"))
                    lineNum = forEachIdx;
                forEachIdx++;
            }
            Object invNode = lines.get(lineNum);
            List<Object> invLines = getNodeChildren(invNode);
            selItems = getSelectedItems(invLines, getAll, recursive);
        } catch (Exception e) {
            consolePrint("Unexpected error while getting selected items - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
        }
        return selItems;
    }

    public static List<InventoryMetaItem> getSelectedItems(InventoryListComponent ilc) {
        return getSelectedItems(ilc, false, true);
    }

    public static List<InventoryMetaItem> getSelectedItems(InventoryListComponent ilc, boolean getAll, boolean recursive) {
        List<InventoryMetaItem> selItems = new ArrayList<>();
        try {
            Object rootNode = getInventoryRootNode(ilc);
            selItems = getSelectedItems(getNodeChildren(rootNode), getAll, recursive);
        } catch (Exception e) {
            consolePrint("Unexpected error while getting selected items - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
        }
        return selItems;
    }

    public static List<InventoryMetaItem> getSelectedItems(List nodes, boolean getAll, boolean recursive) {
        List<InventoryMetaItem> selItems = new ArrayList<>();
        try {
            for (Object currentNode : nodes) {
                boolean isSelected = ((Boolean)getField(currentNode, "isSelected")).booleanValue();
                List<Object> children = getNodeChildren(currentNode);
                Object lineItem = getField(currentNode, "item");
                InventoryMetaItem item = getField(lineItem, "item");
                if (item == null)
                    continue;
                boolean isContainer = ((Boolean)getField(lineItem, "isContainer")).booleanValue();
                boolean isInventoryGroup = ((Boolean)getField(lineItem, "isInventoryGroup")).booleanValue();
                if (children.size() > 0) {
                    if (isContainer && !isInventoryGroup && (getAll || isSelected)) {
                        Object firstChildrenLineItem = getField(children.get(0), "item");
                        InventoryMetaItem firstChildrenItem = getField(firstChildrenLineItem, "item");
                        if (firstChildrenItem == null || firstChildrenItem.getId() != item.getId())
                            selItems.add(item);
                        if (recursive || getAll)
                            selItems.addAll(getSelectedItems(children, true, true));
                        continue;
                    }
                    selItems.addAll(getSelectedItems(children, (getAll || isSelected), recursive));
                    continue;
                }
                if (!isInventoryGroup && (getAll || isSelected))
                    selItems.add(item);
            }
        } catch (Exception e) {
            consolePrint("Unexpected error while getting selected items - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
        }
        return selItems;
    }

    public static InventoryMetaItem getInventoryItem(String itemName) {
        List<InventoryMetaItem> allItems = getSelectedItems(true, true);
        return getInventoryItem(allItems, itemName);
    }

    public static InventoryMetaItem getInventoryItem(InventoryListComponent ilc, String itemName) {
        List<InventoryMetaItem> allItems = getSelectedItems(ilc, true, true);
        return getInventoryItem(allItems, itemName);
    }

    public static InventoryMetaItem getInventoryItem(List<InventoryMetaItem> items, String itemName) {
        try {
            if (items == null || items.size() == 0)
                return null;
            for (InventoryMetaItem invItem : items) {
                if (invItem.getBaseName().startsWith(itemName))
                    return invItem;
            }
            for (InventoryMetaItem invItem : items) {
                if (invItem.getBaseName().contains(itemName) || (itemName.contains("'") && invItem.getDisplayName().contains(itemName.replaceAll("'", ""))))
                    return invItem;
            }
        } catch (Exception e) {
            consolePrint("Got error while searching for " + itemName + " in your inventory. Error - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
        }
        return null;
    }

    public static List<InventoryMetaItem> getInventoryItems(String itemName) {
        List<InventoryMetaItem> allItems = getSelectedItems(true, true);
        return getInventoryItems(allItems, itemName);
    }

    public static List<InventoryMetaItem> getInventoryItems(InventoryListComponent ilc, String itemName) {
        List<InventoryMetaItem> allItems = getSelectedItems(ilc, true, true);
        return getInventoryItems(allItems, itemName);
    }

    public static List<InventoryMetaItem> getInventoryItems(List<InventoryMetaItem> items, String itemName) {
        return getInventoryItems(items, item ->

                (item.getBaseName().contains(itemName) || (itemName.contains("'") && item.getDisplayName().contains(itemName.replaceAll("'", "")))));
    }

    public static List<InventoryMetaItem> getInventoryItems(Predicate<InventoryMetaItem> filter) {
        List<InventoryMetaItem> allItems = getSelectedItems(true, true);
        return getInventoryItems(allItems, filter);
    }

    public static List<InventoryMetaItem> getInventoryItems(InventoryListComponent ilc, Predicate<InventoryMetaItem> filter) {
        List<InventoryMetaItem> allItems = getSelectedItems(ilc, true, true);
        return getInventoryItems(allItems, filter);
    }

    public static List<InventoryMetaItem> getInventoryItems(List<InventoryMetaItem> items, Predicate<InventoryMetaItem> filter) {
        List<InventoryMetaItem> targets = new ArrayList<>();
        try {
            if (items == null || items.size() == 0)
                return targets;
            for (InventoryMetaItem invItem : items) {
                if (filter.test(invItem))
                    targets.add(invItem);
            }
        } catch (Exception e) {
            consolePrint("Got error while searching for items: %s", new Object[] { e.getMessage() });
            consolePrint(e.toString(), new Object[0]);
        }
        return targets;
    }

    public static List<InventoryMetaItem> getInventoryItemsAtPoint(int x, int y) {
        return getInventoryItemsAtPoint(WurmHelper.hud.getInventoryWindow().getInventoryListComponent(), x, y);
    }

    public static List<InventoryMetaItem> getInventoryItemsAtPoint(InventoryListComponent ilc, int x, int y) {
        List<InventoryMetaItem> itemList = new ArrayList<>();
        try {
            WurmTreeList wtl = getField(ilc, "itemList");
            Method getNodeAt = ReflectionUtil.getMethod(wtl.getClass(), "getNodeAt");
            getNodeAt.setAccessible(true);
            Object hoveredNode = getNodeAt.invoke(wtl, new Object[] { Integer.valueOf(x), Integer.valueOf(y) });
            if (hoveredNode != null) {
                List<Object> childLines = getNodeChildren(hoveredNode);
                itemList = getSelectedItems(childLines, true, true);
                Object lineItem = getField(hoveredNode, "item");
                InventoryMetaItem item = getField(lineItem, "item");
                boolean isContainer = ((Boolean)getField(lineItem, "isContainer")).booleanValue();
                if (childLines.size() == 0 || isContainer)
                    itemList.add(item);
            }
        } catch (NoSuchMethodException|IllegalAccessException|NoSuchFieldException|java.lang.reflect.InvocationTargetException e) {
            e.printStackTrace();
        }
        return itemList;
    }

    public static List<InventoryMetaItem> getFirstLevelItems() {
        InventoryListComponent ilc = WurmHelper.hud.getInventoryWindow().getInventoryListComponent();
        try {
            Object rootNode = getInventoryRootNode(ilc);
            List<Object> lines = getNodeChildren(rootNode);
            Object nodeLineItem = getField(lines.get(1), "item");
            InventoryMetaItem nodeItem = getField(nodeLineItem, "item");
            return new ArrayList<>(nodeItem.getChildren());
        } catch (Exception e) {
            consolePrint("getFirstLevelItems() has encountered an error - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
            return new ArrayList<>();
        }
    }

    public static InventoryMetaItem locateToolItem(String toolName) {
        InventoryListComponent mainInventory = WurmHelper.hud.getInventoryWindow().getInventoryListComponent();
        List<InventoryMetaItem> allItems = getSelectedItems(mainInventory, true, true);
        Pattern regex = Pattern.compile(String.format("\\b%s\\b", new Object[] { toolName }));
        List<InventoryMetaItem> items = getInventoryItems(allItems, item -> regex.matcher(item.getBaseName()).find());
        if (items.size() == 0) {
            consolePrint("Error: Couldn't find any tools matching `%s`!", new Object[] { toolName });
            return null;
        }
        if (items.size() > 1)
            consolePrint("Warning: search for tool `%s` matched %d items", new Object[] { toolName, Integer.valueOf(items.size()) });
        return items.get(0);
    }

    public static InventoryMetaItem getRootItem(InventoryListComponent ilc) {
        try {
            Object listRootItem = getField(ilc, "rootItem");
            return getField(listRootItem, "item");
        } catch (IllegalAccessException|NoSuchFieldException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static WurmComponent getTargetComponent(Predicate<WurmComponent> filter) {
        int x = WurmHelper.hud.getWorld().getClient().getXMouse();
        int y = WurmHelper.hud.getWorld().getClient().getYMouse();
        return getComponentAtPoint(x, y, filter);
    }

    public static WurmComponent getComponentAtPoint(int x, int y, Predicate<WurmComponent> filter) {
        try {
            for (int i = 0; i < (WurmHelper.getInstance()).components.size(); ) {
                WurmComponent wurmComponent = (WurmHelper.getInstance()).components.get(i);
                if (!wurmComponent.contains(x, y) || (
                        filter != null && !filter.test(wurmComponent))) {
                    i++;
                    continue;
                }
                return wurmComponent;
            }
        } catch (Exception e) {
            consolePrint("Can't get target component! Error - " + e.getMessage(), new Object[0]);
            consolePrint(e.toString(), new Object[0]);
        }
        return null;
    }

    public static InventoryListComponent getInventoryForComponent(WurmComponent component) {
        InventoryListComponent invComponent;
        if (component == null) {
            consolePrint("Couldn't find an open container under the cursor", new Object[0]);
            return null;
        }
        try {
            invComponent = getField(component, "component");
        } catch (Exception err) {
            consolePrint("Couldn't get container's ListComponent", new Object[0]);
            err.printStackTrace();
            return null;
        }
        if (getRootItem(invComponent) == null) {
            consolePrint("Found a ListComponent but it has no root item(?!)", new Object[0]);
            return null;
        }
        return invComponent;
    }

    public static InventoryListComponent getTargetInventory() {
        int x = WurmHelper.hud.getWorld().getClient().getXMouse();
        int y = WurmHelper.hud.getWorld().getClient().getYMouse();
        return getInventoryAtPoint(x, y);
    }

    public static InventoryListComponent getInventoryAtPoint(int x, int y) {
        WurmComponent invWindow = getComponentAtPoint(x, y, c -> (c instanceof com.wurmonline.client.renderer.gui.ItemListWindow || c instanceof com.wurmonline.client.renderer.gui.InventoryWindow));
        return (invWindow == null) ? null : getInventoryForComponent(invWindow);
    }

    public static int[][] getAreaCoordinates() {
        int m, k, i, area[][] = new int[9][2];
        int x = WurmHelper.hud.getWorld().getPlayerCurrentTileX();
        int y = WurmHelper.hud.getWorld().getPlayerCurrentTileY();
        int direction = Math.round(WurmHelper.hud.getWorld().getPlayerRotX() / 90.0F);
        switch (direction) {
            case 1:
                for (m = 0; m < 3; m++) {
                    for (int n = 0; n < 3; n++) {
                        area[m * 3 + n][0] = x + m - 1;
                        area[m * 3 + n][1] = y + n - 1;
                    }
                }
                return area;
            case 2:
                for (k = 0; k < 3; k++) {
                    for (int n = 0; n < 3; n++) {
                        area[k * 3 + n][0] = x - n + 1;
                        area[k * 3 + n][1] = y + k - 1;
                    }
                }
                return area;
            case 3:
                for (i = 0; i < 3; i++) {
                    for (int n = 0; n < 3; n++) {
                        area[i * 3 + n][0] = x - i + 1;
                        area[i * 3 + n][1] = y - n + 1;
                    }
                }
                return area;
        }
        for (int j = 0; j < 3; j++) {
            for (int n = 0; n < 3; n++) {
                area[j * 3 + n][0] = x + n - 1;
                area[j * 3 + n][1] = y - j + 1;
            }
        }
        return area;
    }

    public static URL getResource(String r) {
        URL url = WurmHelper.class.getClassLoader().getResource(r);
        if (url == null && WurmHelper.class.getClassLoader() == HookManager.getInstance().getLoader()) {
            url = HookManager.getInstance().getClassPool().find(WurmHelper.class.getName());
            if (url != null) {
                String path = url.toString();
                int pos = path.lastIndexOf('!');
                if (pos != -1) {
                    if (r.substring(0, 1).equals("/"))
                        r = r.substring(1);
                    path = path.substring(0, pos) + "!/" + r;
                }
                try {
                    url = new URL(path);
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
            }
        }
        return url;
    }

    public static float getTotalWeight() {
        PaperDollInventory paperDollInventory = WurmHelper.hud.getPaperDollInventory();
        try {
            PaperDollSlot equippedWeightItem = getField(paperDollInventory, "equippedWeightItem");
            InventoryMetaItem inventoryItem = getField(paperDollInventory, "inventoryItem");
            return equippedWeightItem.getWeight() + inventoryItem.getWeight();
        } catch (IllegalAccessException|NoSuchFieldException e) {
            e.printStackTrace();
            return 0.0F;
        }
    }

    public static float getMaxWeight() {
        float bs = SkillLogicSet.getSkill("Body strength").getValue();
        return bs * 7.0F;
    }

    public static long[] getItemIds(List<InventoryMetaItem> container) {
        if (container == null)
            return null;
        long[] ids = new long[container.size()];
        for (int i = 0; i < container.size(); i++)
            ids[i] = ((InventoryMetaItem)container.get(i)).getId();
        return ids;
    }

    public static int getMaxActionNumber() {
        MindLogicCalculator mlc;
        try {
            mlc = getField(WurmHelper.hud, "mindLogicCalculator");
        } catch (IllegalAccessException|NoSuchFieldException e) {
            e.printStackTrace();
            return 0;
        }
        return mlc.getMaxNumberOfActions();
    }

    public static float getPlayerStamina() {
        PlayerObj ply = WurmHelper.hud.getWorld().getPlayer();
        return ply.getStamina() + ply.getDamage();
    }

    public static void writeToConsoleInputLine(String s) {
        try {
            Object consoleComponent = getField(WurmHelper.hud, "consoleComponent");
            Object inputField = getField(consoleComponent, "inputField");
            Method method = inputField.getClass().getDeclaredMethod("setTextMoveToEnd", new Class[] { String.class });
            method.setAccessible(true);
            method.invoke(inputField, new Object[] { s });
        } catch (IllegalAccessException|NoSuchFieldException|NoSuchMethodException|java.lang.reflect.InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    public static boolean printExceptions(ThrowingRunnable fn, String fmt, Object... args) {
        try {
            fn.run();
            return true;
        } catch (Exception err) {
            String callingClass = err.getStackTrace()[2].getClassName();
            consolePrint(
                    String.format("%s: %s", new Object[] { callingClass, fmt }), new Object[] { err
                            .getClass().getName(), err
                            .getMessage(), args });
            err.printStackTrace();
            return false;
        }
    }

    public static void rethrow(ThrowingRunnable fn) {
        try {
            fn.run();
        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }

    public static <T> T rethrow(ThrowingProducer<T> fn) {
        try {
            return fn.get();
        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }

    public static List<CreatureCellRenderable> findCreatures(BiPredicate<CreatureCellRenderable, CreatureData> predicate) {
        List<CreatureCellRenderable> creatures = new ArrayList<>();
        try {
            ServerConnectionListenerClass sscc = WurmHelper.hud.getWorld().getServerConnection().getServerConnectionListener();
            Map<Long, CreatureCellRenderable> creaturesMap = getField(sscc, "creatures");
            for (CreatureCellRenderable creature : creaturesMap.values()) {
                CreatureData data;
                try {
                    data = getField(creature, "creature");
                } catch (Exception e) {
                    consolePrint(e.toString(), new Object[0]);
                    continue;
                }
                if (predicate.test(creature, data))
                    creatures.add(creature);
            }
        } catch (Exception e) {
            consolePrint(e.toString(), new Object[0]);
        }
        return creatures;
    }

    public static float sqdistFromPlayer(CellRenderable obj) {
        float px = WurmHelper.hud.getWorld().getPlayerPosX();
        float py = WurmHelper.hud.getWorld().getPlayerPosY();
        return

                (float)(Math.pow((px - obj.getXPos()), 2.0D) + Math.pow((py - obj.getYPos()), 2.0D));
    }

    public static boolean isNearbyPlayer(CellRenderable obj) {
        return (sqdistFromPlayer(obj) < 25.0F);
    }

    private static final String[] groomableCreatureNames = new String[] {
            "bison", "bull", "calf", "chicken", "cow", "deer", "dog", "foal", "hen", "horse",
            "lamb", "pig", "ram", "rooster", "sheep", "unicorn" };

    public static boolean isGroomableCreature(CreatureCellRenderable creature) {
        String name = creature.getHoverName().toLowerCase();
        if (name.contains("hell "))
            return false;
        return
                Arrays.<String>stream(groomableCreatureNames)
                        .anyMatch(allowed -> name.contains(allowed));
    }

    @FunctionalInterface
    public static interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    public static interface ThrowingProducer<T> {
        T get() throws Exception;
    }

    public static class Cell<T> {
        public T val;

        public Cell(T val) {
            this.val = val;
        }
    }

    public static class Vec2i {
        public int x;

        public int y;

        public Vec2i() {
            this.x = 0;
            this.y = 0;
        }

        public Vec2i(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int hashCode() {
            return 31 * Integer.hashCode(this.x) + Integer.hashCode(this.y);
        }

        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || !(obj instanceof Vec2i))
                return false;
            Vec2i rhs = (Vec2i)obj;
            return (this.x == rhs.x && this.y == rhs.y);
        }

        public String toString() {
            return String.format("Vec2i(%d, %d)", new Object[] { Integer.valueOf(this.x), Integer.valueOf(this.y) });
        }
    }
}
