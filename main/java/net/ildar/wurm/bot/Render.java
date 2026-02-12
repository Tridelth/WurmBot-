package net.ildar.wurm.bot;

import com.wurmonline.client.game.SeasonManager.Season;
import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.classhooks.InvocationHandlerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Render extends Bot {

    private static final Logger logger = Logger.getLogger(Render.class.getSimpleName());
    private static volatile boolean renderWeather = true;
    private static volatile boolean renderSky = true;
    private static volatile boolean renderTerrain = true;
    private static volatile boolean renderCave = true;
    private static volatile boolean renderWater = true;
    private static volatile boolean renderParticles = true;
    private static volatile boolean renderGrass = true;
    private static volatile boolean renderTrees = true;
    private static volatile boolean autoChangeSeasons = true;
    private static volatile Season lastObservedAutoSeason = null;
    private static final long AUTO_SEASON_CHECK_INTERVAL_MS = 10_000L;
    private static volatile long lastAutoSeasonCheckMs = 0L;
    private static final ThreadLocal<Boolean> skipRawTypeRemapTl = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> inTerrainRenderTl = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> inTerrainTextureTl = new ThreadLocal<Boolean>();
    private static volatile int lockSeasonIndex = -1;
    private enum FakeSeason {AUTO, WINTER}
    private static volatile FakeSeason fakeSeason = FakeSeason.AUTO;
    private static volatile boolean tileMapInitialized = false;
    private static byte TILE_SNOW_ID = (byte) -1;
    private static volatile Object TILE_SNOW_TILE_OBJ = null;
    private static volatile boolean pendingTerrainTextureCacheClear = false;
    private static volatile boolean pendingBillboardReload = false;
    private static volatile boolean pendingLodReset = false;
    private static volatile boolean pendingWorldReset = false;
    private static final Set<Byte> winterRemapFrom = new HashSet<Byte>();
    private static final Set<Byte> winterKeepTileType = new HashSet<Byte>();
    private static long lastTreeAtlasRefreshMs = 0L;
    private static final long TREE_ATLAS_REFRESH_COOLDOWN_MS = 5_000L;
    private static long lastBillboardReloadMs = 0L;
    private static final long BILLBOARD_RELOAD_COOLDOWN_MS = 5_000L;
    private static long lastLodResetMs = 0L;
    private static final long LODRESET_COOLDOWN_MS = 4_000L;
    private static long lastWorldResetMs = 0L;
    private static final long WORLDRESET_COOLDOWN_MS = 10_000L;
    private static volatile Object lastTreeMapTextureObj = null;
    private static volatile Object lastBushMapTextureObj = null;
    private static volatile WeakReference<Object> lastCellRendererRef = new WeakReference<Object>(null);
    private static final Set<String> WINTER_TERRAIN_TEXTURE_KEY_BASES = new HashSet<String>();
    static {
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.grass".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.grass.lawn".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.tundra".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.steppe".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.forest".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.rock".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.rock.cliff".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.dirt".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.dirt.packed".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.gravel".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.sand".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.moss".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.grass.reed".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.peat".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.farm".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.planks".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.planks.tarred".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.mycelium".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.forest.mycelium".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.marsh".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.stoneslabs".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.cobblestone".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.cobble2".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.cobble3".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.slateslabs".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.slatebricks".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.sandstoneslabs".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.sandstonebricks".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.marbleslabs".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.marblebricks".toLowerCase(Locale.US));
        WINTER_TERRAIN_TEXTURE_KEY_BASES.add("img.texture.terrain.potterybricks".toLowerCase(Locale.US));
    }

    private static boolean isSimpleValueType(Class<?> c) {
        if (c == null) return true;
        if (c.isPrimitive()) return true;
        if (c.isEnum()) return true;
        return c == String.class || Number.class.isAssignableFrom(c) || c == Boolean.class || c == Character.class;
    }
    public static void setAutoChangeSeasons(boolean enabled) {
        autoChangeSeasons = enabled;
    }
    private static Season tryGetCurrentGameSeasonBestEffort() {
        try {
            Object hud = WurmHelper.hud;
            if (hud == null) return null;

            Object world = WurmHelper.hud.getWorld();
            if (world == null) return null;

            Method getSeasonManager = world.getClass().getMethod("getSeasonManager");
            Object sm = getSeasonManager.invoke(world);
            if (sm == null) return null;

            Method getSeason = sm.getClass().getMethod("getSeason");
            Object v = getSeason.invoke(sm);
            return (v instanceof Season) ? (Season) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static void maybeAutoRefreshOnSeasonChangeActive() {
        if (!autoChangeSeasons) return;

        // Manual lock wins; AUTO watcher only runs when unlocked
        if (lockSeasonIndex >= 0) return;

        long now = System.currentTimeMillis();
        if (now - lastAutoSeasonCheckMs < AUTO_SEASON_CHECK_INTERVAL_MS) return;
        lastAutoSeasonCheckMs = now;

        Season current = tryGetCurrentGameSeasonBestEffort();
        if (current == null) return;

        Season prev = lastObservedAutoSeason;
        if (prev == current) return;

        lastObservedAutoSeason = current;

        // Skip doing heavy refresh on first observation (login/startup)
        if (prev == null) return;

        try {
            resetFakeSeasonTileRemap();
            flushTextureCachesForSeasonChange();

            pendingTerrainTextureCacheClear = true;
            pendingBillboardReload = true;
            pendingLodReset = true;
            pendingWorldReset = true;

            forceVegetationHardReset();

            Utils.consolePrint("AUTO season detected change (%s -> %s). Visuals refreshed.", prev.name(), current.name());
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Auto season refresh failed", t);
        }
    }
    private static Object invokeOriginal(Method method, Object proxy, Object[] args) throws Throwable {
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            return method.invoke(proxy, args);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause();
            throw (c != null) ? c : ite;
        }
    }

    private static boolean hasGlContextOnThisThread() {
        try {
            return org.lwjgl.opengl.Display.isCurrent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object tryInvokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findFieldValueByTypeName(Object instance, String wantedTypeName) {
        if (instance == null || wantedTypeName == null) return null;
        Class<?> c = instance.getClass();

        while (c != null) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (f == null) continue;
                    Class<?> ft = f.getType();
                    if (ft == null) continue;
                    if (!wantedTypeName.equals(ft.getName())) continue;

                    f.setAccessible(true);
                    Object v = f.get(instance);
                    if (v != null) return v;
                }
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object findObjectByTypeNameDeep(Object root, String wantedTypeName, int maxDepth, int maxNodes) {
        if (root == null || wantedTypeName == null || maxDepth < 0) return null;
        if (maxNodes < 10) maxNodes = 10;

        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        int[] nodesLeft = new int[]{maxNodes};
        return findObjectByTypeNameDeep0(root, wantedTypeName, maxDepth, seen, nodesLeft);
    }

    private static Object findObjectByTypeNameDeep0(
            Object obj,
            String wantedTypeName,
            int depthLeft,
            IdentityHashMap<Object, Boolean> seen,
            int[] nodesLeft
    ) {
        if (obj == null) return null;
        if (nodesLeft[0]-- <= 0) return null;
        if (seen.containsKey(obj)) return null;
        seen.put(obj, Boolean.TRUE);

        Class<?> cls = obj.getClass();
        if (cls != null && wantedTypeName.equals(cls.getName())) return obj;
        if (depthLeft <= 0) return null;

        if (cls.isArray()) {
            try {
                int len = java.lang.reflect.Array.getLength(obj);
                for (int i = 0; i < len; i++) {
                    Object v = java.lang.reflect.Array.get(obj, i);
                    if (v == null) continue;
                    Object found = findObjectByTypeNameDeep0(v, wantedTypeName, depthLeft - 1, seen, nodesLeft);
                    if (found != null) return found;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        if (obj instanceof Iterable) {
            try {
                Iterator<?> it = ((Iterable<?>) obj).iterator();
                int n = 0;
                while (it.hasNext() && n++ < 200) {
                    Object v = it.next();
                    if (v == null) continue;
                    Object found = findObjectByTypeNameDeep0(v, wantedTypeName, depthLeft - 1, seen, nodesLeft);
                    if (found != null) return found;
                }
            } catch (Throwable ignored) {
            }
        }

        if (obj instanceof Map) {
            try {
                Map m = (Map) obj;
                int n = 0;
                for (Object eObj : m.entrySet()) {
                    if (eObj == null) continue;
                    if (++n > 200) break;
                    if (eObj instanceof Map.Entry) {
                        Map.Entry e = (Map.Entry) eObj;
                        Object k = e.getKey();
                        Object v = e.getValue();
                        if (k != null) {
                            Object foundK = findObjectByTypeNameDeep0(k, wantedTypeName, depthLeft - 1, seen, nodesLeft);
                            if (foundK != null) return foundK;
                        }
                        if (v != null) {
                            Object foundV = findObjectByTypeNameDeep0(v, wantedTypeName, depthLeft - 1, seen, nodesLeft);
                            if (foundV != null) return foundV;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        Class<?> c = cls;
        while (c != null) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                fields = null;
            }

            if (fields != null) {
                for (Field f : fields) {
                    if (f == null) continue;

                    Class<?> ft;
                    try {
                        ft = f.getType();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (ft == null || isSimpleValueType(ft)) continue;

                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v == null) continue;

                        Object found = findObjectByTypeNameDeep0(v, wantedTypeName, depthLeft - 1, seen, nodesLeft);
                        if (found != null) return found;
                    } catch (Throwable ignored) {
                    }
                }
            }
            c = c.getSuperclass();
        }

        return null;
    }

    private static String lockedSeasonAppendix(Season s) {
        if (s == null) return "";
        String name = s.name();
        if ("WINTER".equals(name)) return ".winter";
        if ("FALL".equals(name)) return ".fall";
        if ("SPRING".equals(name)) return ".spring";
        return "";
    }

    private static String forcedSeasonAppendixOrEmpty() {
        int idx = lockSeasonIndex;
        if (idx < 0) return "";
        Season[] seasons = Season.values();
        if (idx >= seasons.length) return "";
        return lockedSeasonAppendix(seasons[idx]);
    }

    private static String forceSeasonTreeBushMapKey(String key) {
        if (key == null) return null;

        String appendix = forcedSeasonAppendixOrEmpty();
        if (appendix.isEmpty()) return key;

        String k = key.trim();
        if (k.isEmpty()) return k;

        String kl = k.toLowerCase(Locale.US);

        boolean isTreeMap = kl.equals("img.terrain.treemap");
        boolean isBushMap = kl.equals("img.terrain.bushmap");
        if (!isTreeMap && !isBushMap) return k;

        if (kl.endsWith(".winter") || kl.endsWith(".fall") || kl.endsWith(".spring")) return k;

        return k + appendix;
    }

    private static String forceSeasonTerrainTextureKey(String key) {
        if (key == null) return null;

        String appendix = forcedSeasonAppendixOrEmpty();
        if (!".winter".equals(appendix)) return key;

        String k = key.trim();
        if (k.isEmpty()) return k;

        String kl = k.toLowerCase(Locale.US);

        if (kl.endsWith(".winter") || kl.endsWith(".fall") || kl.endsWith(".spring")) return k;

        if (kl.startsWith("img.texture.terrain.")) {
            if (!WINTER_TERRAIN_TEXTURE_KEY_BASES.contains(kl)) return k;
            return k + ".winter";
        }

        return k;
    }

    private static void maybeRemapKeysInPlace(Object[] args, int stringArgIndex) {
        try {
            if (args == null) return;
            if (stringArgIndex < 0 || stringArgIndex >= args.length) return;
            if (!(args[stringArgIndex] instanceof String)) return;

            String key = (String) args[stringArgIndex];

            String remappedTerrain = forceSeasonTerrainTextureKey(key);
            if (remappedTerrain != null && !remappedTerrain.equals(key)) {
                args[stringArgIndex] = remappedTerrain;
                return;
            }

            String remapped = forceSeasonTreeBushMapKey(key);
            if (remapped != null && !remapped.equals(key)) {
                args[stringArgIndex] = remapped;
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean looksLikeTreeOrBushTile(Object tileObj) {
        if (tileObj == null) return false;
        try {
            String s = String.valueOf(tileObj);
            if (s == null) return false;
            return s.startsWith("TILE_TREE") || s.startsWith("TILE_BUSH") || s.startsWith("TILE_FRUIT_TREE")
                    || s.startsWith("TILE_ENCHANTED_TREE") || s.startsWith("TILE_ENCHANTED_BUSH") || s.startsWith("TILE_ENCHANTED_FRUIT_TREE")
                    || s.startsWith("TILE_MYCELIUM_TREE") || s.startsWith("TILE_MYCELIUM_BUSH") || s.startsWith("TILE_MYCELIUM_FRUIT_TREE");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object remapTileObjForTerrainTexturing(Object origTileObj) {
        if (fakeSeason != FakeSeason.WINTER) return origTileObj;

        Boolean inTex = inTerrainTextureTl.get();
        if (inTex == null || !inTex.booleanValue()) return origTileObj;

        if (!looksLikeTreeOrBushTile(origTileObj)) return origTileObj;

        ensureTileMapInitialized();
        Object snow = TILE_SNOW_TILE_OBJ;
        return (snow != null) ? snow : origTileObj;
    }

    private static void maybeRemapTileArgForTerrainTexturing(Object[] args, int tileArgIndex) {
        try {
            if (args == null) return;
            if (tileArgIndex < 0 || tileArgIndex >= args.length) return;
            Object t = args[tileArgIndex];
            Object r = remapTileObjForTerrainTexturing(t);
            if (r != t) args[tileArgIndex] = r;
        } catch (Throwable ignored) {
        }
    }

    private static void resetFakeSeasonTileRemap() {
        synchronized (Render.class) {
            tileMapInitialized = false;
            winterRemapFrom.clear();
            winterKeepTileType.clear();
            TILE_SNOW_ID = (byte) -1;
            TILE_SNOW_TILE_OBJ = null;
        }
    }

    private static void ensureTileMapInitialized() {
        if (tileMapInitialized) return;
        synchronized (Render.class) {
            if (tileMapInitialized) return;

            try {
                ClassLoader loader = HookManager.getInstance().getLoader();
                Class<?> tileEnum = Class.forName("com.wurmonline.mesh.Tiles$Tile", false, loader);

                TILE_SNOW_ID = resolveTileId(tileEnum, "TILE_SNOW");

                try {
                    TILE_SNOW_TILE_OBJ = Enum.valueOf((Class<? extends Enum>) tileEnum, "TILE_SNOW");
                } catch (Throwable ignored) {
                    TILE_SNOW_TILE_OBJ = null;
                }

                addIfPresent(tileEnum, "TILE_GRASS");
                addIfPresent(tileEnum, "TILE_STEPPE");
                addIfPresent(tileEnum, "TILE_LAWN");

                addKeepTileTypeIfPresent(tileEnum, "TILE_GRASS");
                addKeepTileTypeIfPresent(tileEnum, "TILE_STEPPE");
                addKeepTileTypeIfPresent(tileEnum, "TILE_LAWN");

                addIfPresent(tileEnum, "TILE_DIRT");
                addIfPresent(tileEnum, "TILE_SAND");
                addIfPresent(tileEnum, "TILE_TUNDRA");
                addIfPresent(tileEnum, "TILE_MOSS");
                addIfPresent(tileEnum, "TILE_PEAT");
                addIfPresent(tileEnum, "TILE_CLAY");

                tileMapInitialized = true;
            } catch (Throwable t) {
                tileMapInitialized = true;
            }
        }
    }

    private static void addIfPresent(Class<?> tileEnum, String enumName) {
        try {
            byte id = resolveTileId(tileEnum, enumName);
            winterRemapFrom.add(Byte.valueOf(id));
        } catch (Throwable ignored) {
        }
    }

    private static void addKeepTileTypeIfPresent(Class<?> tileEnum, String enumName) {
        try {
            byte id = resolveTileId(tileEnum, enumName);
            winterKeepTileType.add(Byte.valueOf(id));
        } catch (Throwable ignored) {
        }
    }

    private static byte tryGetTileIdFromTileObj(Object tileObj) {
        if (tileObj == null) return (byte) -1;
        Class<?> c = tileObj.getClass();

        try {
            Method getId = c.getDeclaredMethod("getId");
            getId.setAccessible(true);
            Object v = getId.invoke(tileObj);
            if (v instanceof Byte) return ((Byte) v).byteValue();
            if (v instanceof Number) return ((Number) v).byteValue();
        } catch (Throwable ignored) {
        }

        try {
            Field f = c.getDeclaredField("id");
            f.setAccessible(true);
            Object v = f.get(tileObj);
            if (v instanceof Byte) return ((Byte) v).byteValue();
            if (v instanceof Number) return ((Number) v).byteValue();
        } catch (Throwable ignored) {
        }

        return (byte) -1;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static byte resolveTileId(Class<?> tileEnum, String enumName) throws Exception {
        Object constant = Enum.valueOf((Class<? extends Enum>) tileEnum, enumName);

        try {
            Method getId = tileEnum.getDeclaredMethod("getId");
            getId.setAccessible(true);
            Object v = getId.invoke(constant);
            if (v instanceof Byte) return ((Byte) v).byteValue();
            if (v instanceof Number) return ((Number) v).byteValue();
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Field f = tileEnum.getDeclaredField("id");
            f.setAccessible(true);
            Object v = f.get(constant);
            if (v instanceof Byte) return ((Byte) v).byteValue();
            if (v instanceof Number) return ((Number) v).byteValue();
        } catch (NoSuchFieldException ignored) {
        }

        throw new IllegalStateException("Cannot resolve tile id for " + enumName);
    }

    private static byte remapRawTypeForFakeSeason(byte rawType) {
        if (fakeSeason != FakeSeason.WINTER) return rawType;

        Boolean skip = skipRawTypeRemapTl.get();
        if (skip != null && skip.booleanValue()) return rawType;

        ensureTileMapInitialized();
        if ((TILE_SNOW_ID & 0xFF) == 0xFF) return rawType;

        if (winterRemapFrom.contains(Byte.valueOf(rawType))) return TILE_SNOW_ID;
        return rawType;
    }

    private static void invokeNoArgIfExists(Object target, String methodName) {
        if (target == null || methodName == null) return;
        try {
            Method m = target.getClass().getDeclaredMethod(methodName);
            m.setAccessible(true);
            m.invoke(target);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }
    }

    private static void invokeIfExists(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        if (target == null || methodName == null) return;
        try {
            Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }
    }

    private static void invokeNoArgIfExistsAny(Object target, String... methodNames) {
        if (target == null || methodNames == null) return;
        for (String n : methodNames) {
            if (n == null) continue;
            invokeNoArgIfExists(target, n);
        }
    }

    private static void forceSeasonVisualRefresh() {
        try {
            Object world = WurmHelper.hud.getWorld();
            if (world == null) return;

            Object terrainRenderer = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.terrain.TerrainRenderer");
            Object decorationRenderer = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.terrain.decorator.DecorationRenderer");
            Object surfaceCell = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.cell.SurfaceCell");
            Object caveRender = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.cave.CaveRender");

            invokeNoArgIfExists(terrainRenderer, "refresh");
            invokeNoArgIfExists(decorationRenderer, "setAllDirty");

            try {
                if (decorationRenderer != null) {
                    Class<?> drc = decorationRenderer.getClass();

                    Field fDirty = null;
                    Field fLastX = null;
                    Field fLastY = null;

                    try { fDirty = drc.getDeclaredField("dirty"); } catch (Throwable ignored) {}
                    try { fLastX = drc.getDeclaredField("lastPlayerX"); } catch (Throwable ignored) {}
                    try { fLastY = drc.getDeclaredField("lastPlayerY"); } catch (Throwable ignored) {}

                    if (fDirty != null) {
                        fDirty.setAccessible(true);
                        fDirty.setBoolean(decorationRenderer, true);
                    }
                    if (fLastX != null) {
                        fLastX.setAccessible(true);
                        fLastX.setInt(decorationRenderer, 0);
                    }
                    if (fLastY != null) {
                        fLastY.setAccessible(true);
                        fLastY.setInt(decorationRenderer, 0);
                    }
                }
            } catch (Throwable ignored) {
            }

            invokeNoArgIfExists(surfaceCell, "refresh");
            invokeNoArgIfExists(caveRender, "setAllDirty");

            invokeNoArgIfExists(surfaceCell, "rebuildTreeVolume");
            invokeIfExists(surfaceCell, "setDirty", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
        } catch (Throwable ignored) {
        }
    }
    private static int clearTreePositionStaticBillboardTextures() {
        int cleared = 0;
        try {
            if (!hasGlContextOnThisThread()) return 0;

            ClassLoader loader = HookManager.getInstance().getLoader();
            Class<?> tp = Class.forName("com.wurmonline.client.renderer.cell.TreePosition", false, loader);

            for (String fn : new String[]{"treeTexture", "bushTexture"}) {
                try {
                    Field f = tp.getDeclaredField(fn);
                    f.setAccessible(true);
                    Object cur = f.get(null);
                    if (cur != null) {
                        f.set(null, null);
                        cleared++;
                    }
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return cleared;
    }
    private static void setTreePositionStaticBillboardTextures(String treeKey, String bushKey) {
        if (treeKey == null || bushKey == null) return;

        try {
            if (!hasGlContextOnThisThread()) return;

            ClassLoader loader = HookManager.getInstance().getLoader();
            Class<?> tp = Class.forName("com.wurmonline.client.renderer.cell.TreePosition", false, loader);
            Class<?> rtl = Class.forName("com.wurmonline.client.resources.textures.ResourceTextureLoader", false, loader);

            Method getTexture = rtl.getMethod("getTexture", String.class);

            Object treeTex = getTexture.invoke(null, treeKey);
            Object bushTex = getTexture.invoke(null, bushKey);

            Field fTree = tp.getDeclaredField("treeTexture");
            Field fBush = tp.getDeclaredField("bushTexture");
            fTree.setAccessible(true);
            fBush.setAccessible(true);

            fTree.set(null, treeTex);
            fBush.set(null, bushTex);
        } catch (Throwable ignored) {
        }
    }
    private static void clearTerrainTextureStaticCaches() {
        try {
            ClassLoader loader = HookManager.getInstance().getLoader();
            Class<?> tt = Class.forName("com.wurmonline.client.renderer.terrain.TerrainTexture", false, loader);

            try {
                Field f = tt.getDeclaredField("textures");
                f.setAccessible(true);
                Object arr = f.get(null);
                if (arr instanceof Object[]) {
                    Object[] a = (Object[]) arr;
                    for (int i = 0; i < a.length; i++) a[i] = null;
                }
            } catch (Throwable ignored) {
            }

            try {
                Field f = tt.getDeclaredField("textureNames");
                f.setAccessible(true);
                Object arr = f.get(null);
                if (arr instanceof Object[]) {
                    Object[] a = (Object[]) arr;
                    for (int i = 0; i < a.length; i++) a[i] = null;
                }
            } catch (Throwable ignored) {
            }

            try {
                Field f = tt.getDeclaredField("normalMaps");
                f.setAccessible(true);
                Object arr = f.get(null);
                if (arr instanceof Object[]) {
                    Object[] a = (Object[]) arr;
                    for (int i = 0; i < a.length; i++) a[i] = null;
                }
            } catch (Throwable ignored) {
            }

            try {
                Class<?> tpx = Class.forName("com.wurmonline.client.renderer.terrain.TilePropertiesXml", false, loader);

                Object defaultProps = null;
                try {
                    Method findTile = tpx.getMethod("findTile", String.class);
                    defaultProps = findTile.invoke(null, "default");
                } catch (Throwable ignored) {
                }

                if (defaultProps == null) {
                    try {
                        Field plain = tpx.getDeclaredField("plainProperties");
                        plain.setAccessible(true);
                        defaultProps = plain.get(null);
                    } catch (Throwable ignored) {
                    }
                }

                if (defaultProps != null) {
                    Field f = tt.getDeclaredField("properties");
                    f.setAccessible(true);
                    Object arr = f.get(null);
                    if (arr instanceof Object[]) {
                        Object[] a = (Object[]) arr;
                        for (int i = 0; i < a.length; i++) a[i] = defaultProps;
                    }
                }
            } catch (Throwable ignored) {
            }

            try {
                Field f = tt.getDeclaredField("textureNormalmap");
                f.setAccessible(true);
                Object m = f.get(null);
                if (m instanceof Map) {
                    ((Map) m).clear();
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static void flushTextureCachesForSeasonChange() {
        try {
            Object world = WurmHelper.hud.getWorld();
            if (world == null) return;

            Method getClient = world.getClass().getMethod("getClient");
            Object clientBase = getClient.invoke(world);
            if (clientBase == null) return;

            Object ctl = null;
            try {
                Method getTextureLoader = clientBase.getClass().getMethod("getTextureLoader");
                ctl = getTextureLoader.invoke(clientBase);
            } catch (Throwable ignored) {
            }

            if (ctl != null) {
                try {
                    Method unloadTextures = ctl.getClass().getDeclaredMethod("unloadTextures");
                    unloadTextures.setAccessible(true);
                    unloadTextures.invoke(ctl);
                } catch (Throwable ignored) {
                }
            }

            try {
                ClassLoader loader = HookManager.getInstance().getLoader();
                Class<?> rtl = Class.forName("com.wurmonline.client.resources.textures.ResourceTextureLoader", false, loader);

                Method unload = rtl.getDeclaredMethod("unload");
                unload.setAccessible(true);
                unload.invoke(null);
            } catch (Throwable ignored) {
            }

            try {
                ClassLoader loader = HookManager.getInstance().getLoader();
                Class<?> mrl = Class.forName("com.wurmonline.client.renderer.model.ModelResourceLoader", false, loader);
                Method unload = mrl.getDeclaredMethod("unload");
                unload.setAccessible(true);
                unload.invoke(null);
            } catch (Throwable ignored) {
            }

            forceSeasonVisualRefresh();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "flushTextureCachesForSeasonChange failed", t);
        }
    }

    private static Object getLastCellRendererOrNull() {
        try {
            WeakReference<Object> ref = lastCellRendererRef;
            return ref == null ? null : ref.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int replaceExactObjectReferences(Object root, Object from, Object to, int maxDepth, int maxNodes) {
        if (root == null || from == null || to == null) return 0;
        if (from == to) return 0;

        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        int[] nodesLeft = new int[]{Math.max(100, maxNodes)};
        return replaceExactObjectReferences0(root, from, to, maxDepth, seen, nodesLeft);
    }

    private static int replaceExactObjectReferences0(
            Object obj,
            Object from,
            Object to,
            int depthLeft,
            IdentityHashMap<Object, Boolean> seen,
            int[] nodesLeft
    ) {
        if (obj == null) return 0;
        if (nodesLeft[0]-- <= 0) return 0;
        if (seen.containsKey(obj)) return 0;
        seen.put(obj, Boolean.TRUE);
        if (depthLeft <= 0) return 0;

        int replaced = 0;
        Class<?> cls = obj.getClass();
        if (cls == null) return 0;

        if (cls.isArray()) {
            try {
                int len = java.lang.reflect.Array.getLength(obj);
                for (int i = 0; i < len; i++) {
                    Object v = java.lang.reflect.Array.get(obj, i);
                    if (v == null) continue;

                    if (v == from) {
                        java.lang.reflect.Array.set(obj, i, to);
                        replaced++;
                        continue;
                    }

                    if (!isSimpleValueType(v.getClass())) {
                        replaced += replaceExactObjectReferences0(v, from, to, depthLeft - 1, seen, nodesLeft);
                    }
                }
            } catch (Throwable ignored) {
            }
            return replaced;
        }

        if (obj instanceof Iterable) {
            try {
                Iterator<?> it = ((Iterable<?>) obj).iterator();
                int n = 0;
                while (it.hasNext() && n++ < 200) {
                    Object v = it.next();
                    if (v == null) continue;
                    if (!isSimpleValueType(v.getClass())) {
                        replaced += replaceExactObjectReferences0(v, from, to, depthLeft - 1, seen, nodesLeft);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (obj instanceof Map) {
            try {
                Map m = (Map) obj;
                int n = 0;
                for (Object eObj : m.entrySet()) {
                    if (eObj == null) continue;
                    if (++n > 200) break;
                    if (eObj instanceof Map.Entry) {
                        Map.Entry e = (Map.Entry) eObj;
                        Object k = e.getKey();
                        Object v = e.getValue();
                        if (k != null && !isSimpleValueType(k.getClass())) {
                            replaced += replaceExactObjectReferences0(k, from, to, depthLeft - 1, seen, nodesLeft);
                        }
                        if (v != null && !isSimpleValueType(v.getClass())) {
                            replaced += replaceExactObjectReferences0(v, from, to, depthLeft - 1, seen, nodesLeft);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        Class<?> c = cls;
        while (c != null) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                fields = null;
            }

            if (fields != null) {
                for (Field f : fields) {
                    if (f == null) continue;

                    Class<?> ft;
                    try {
                        ft = f.getType();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (ft == null || isSimpleValueType(ft)) continue;

                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v == null) continue;

                        if (v == from) {
                            f.set(obj, to);
                            replaced++;
                            continue;
                        }

                        if (!isSimpleValueType(v.getClass())) {
                            replaced += replaceExactObjectReferences0(v, from, to, depthLeft - 1, seen, nodesLeft);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            c = c.getSuperclass();
        }

        return replaced;
    }

    private static Object findWorldRenderBestEffort(Object world) {
        if (world == null) return null;
        final String WR = "com.wurmonline.client.renderer.WorldRender";

        Object wr = findFieldValueByTypeName(world, WR);
        if (wr != null) return wr;

        return findObjectByTypeNameDeep(world, WR, 10, 20000);
    }

    private static void resetDistantBillboardsNowOnGlThread() {
        if (!hasGlContextOnThisThread()) return;

        Object cr = getLastCellRendererOrNull();
        if (cr == null) {
            try {
                Object world = (WurmHelper.hud == null) ? null : WurmHelper.hud.getWorld();
                Object wr = (world == null) ? null : findWorldRenderBestEffort(world);
                cr = (wr == null) ? null : tryInvokeNoArg(wr, "getCellRenderer");
            } catch (Throwable ignored) {
                cr = null;
            }
        }
        if (cr == null) return;

        // Clear + rebind static billboard atlas references so distant trees can resolve correct textures again.
        clearTreePositionStaticBillboardTextures();

        try {
            Object world = (WurmHelper.hud == null) ? null : WurmHelper.hud.getWorld();
            String appendix = "";
            if (world != null) {
                try {
                    Method getSeasonManager = world.getClass().getMethod("getSeasonManager");
                    Object sm = getSeasonManager.invoke(world);
                    if (sm != null) {
                        Method getSeasonAppendix = sm.getClass().getMethod("getSeasonAppendix");
                        Object v = getSeasonAppendix.invoke(sm);
                        appendix = (v instanceof String) ? (String) v : "";
                    }
                } catch (Throwable ignored) {
                }
            }

            setTreePositionStaticBillboardTextures(
                    "img.terrain.treemap" + appendix,
                    "img.terrain.bushmap" + appendix
            );
        } catch (Throwable ignored) {
        }

        // Now ask CellRenderer to rebuild caches
        invokeNoArgIfExistsAny(cr, "requestCleanup", "setAllDirty", "refresh");
    }

    private static void tryReloadBillboardTexturesOnGlThread() {
        if (!pendingBillboardReload) return;

        long now = System.currentTimeMillis();
        if (now - lastBillboardReloadMs < BILLBOARD_RELOAD_COOLDOWN_MS) {
            pendingBillboardReload = false;
            return;
        }

        if (!hasGlContextOnThisThread()) return;

        pendingBillboardReload = false;
        lastBillboardReloadMs = now;

        try {
            resetDistantBillboardsNowOnGlThread();
        } catch (Throwable ignored) {
        }

        try {
            Object world = (WurmHelper.hud == null) ? null : WurmHelper.hud.getWorld();
            if (world == null) return;

            Object prevTree = lastTreeMapTextureObj;
            Object prevBush = lastBushMapTextureObj;

            String appendix = "";
            try {
                Method getSeasonManager = world.getClass().getMethod("getSeasonManager");
                Object sm = getSeasonManager.invoke(world);
                if (sm != null) {
                    Method getSeasonAppendix = sm.getClass().getMethod("getSeasonAppendix");
                    Object v = getSeasonAppendix.invoke(sm);
                    appendix = (v instanceof String) ? (String) v : "";
                }
            } catch (Throwable ignored) {
            }

            String treeKey = "img.terrain.treemap" + appendix;
            String bushKey = "img.terrain.bushmap" + appendix;

            ClassLoader loader = HookManager.getInstance().getLoader();
            Class<?> rtl = Class.forName("com.wurmonline.client.resources.textures.ResourceTextureLoader", false, loader);
            Method getTexture = rtl.getMethod("getTexture", String.class);

            Object treeTex = getTexture.invoke(null, treeKey);
            Object bushTex = getTexture.invoke(null, bushKey);

            lastTreeMapTextureObj = treeTex;
            lastBushMapTextureObj = bushTex;

            int rebound = 0;

            Object worldRender = findWorldRenderBestEffort(world);
            Object terrainRenderer = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.terrain.TerrainRenderer");
            Object terrainLod = findFieldValueByTypeName(terrainRenderer, "com.wurmonline.client.renderer.terrain.TerrainLod");
            if (terrainLod == null) terrainLod = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.terrain.TerrainLod");

            Object innerLod = null;
            try {
                innerLod = (terrainRenderer == null) ? null : tryInvokeNoArg(terrainRenderer, "getInnerLod");
            } catch (Throwable ignored) {
                innerLod = null;
            }

            Object surfaceCell = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.cell.SurfaceCell");

            Object[] roots = new Object[]{
                    world,
                    worldRender,
                    terrainRenderer,
                    terrainLod,
                    innerLod,
                    surfaceCell
            };

            if (prevTree != null && treeTex != null) {
                for (Object root : roots) {
                    if (root == null) continue;
                    rebound += replaceExactObjectReferences(root, prevTree, treeTex, 12, 60000);
                }
            }
            if (prevBush != null && bushTex != null) {
                for (Object root : roots) {
                    if (root == null) continue;
                    rebound += replaceExactObjectReferences(root, prevBush, bushTex, 12, 60000);
                }
            }

            forceSeasonVisualRefresh();
        } catch (Throwable ignored) {
        }
    }

    private static void tryLodResetOnGlThread() {
        if (!pendingLodReset) return;

        long now = System.currentTimeMillis();
        if (now - lastLodResetMs < LODRESET_COOLDOWN_MS) {
            pendingLodReset = false;
            return;
        }

        if (!hasGlContextOnThisThread()) return;

        pendingLodReset = false;
        lastLodResetMs = now;

        try {
            Object world = (WurmHelper.hud == null) ? null : WurmHelper.hud.getWorld();
            if (world == null) return;

            Object worldRender = findWorldRenderBestEffort(world);
            if (worldRender == null) return;

            Object terrainRenderer = null;
            try {
                terrainRenderer = tryInvokeNoArg(worldRender, "getTerrainRenderer");
            } catch (Throwable ignored) {
                terrainRenderer = null;
            }
            if (terrainRenderer == null) return;

            Object innerLod = null;
            try {
                innerLod = tryInvokeNoArg(terrainRenderer, "getInnerLod");
            } catch (Throwable ignored) {
                innerLod = null;
            }
            if (innerLod == null) return;

            Object near = tryInvokeNoArg(world, "getNearTerrainBuffer");
            Object distant = tryInvokeNoArg(world, "getDistantTerrainBuffer");
            if (near == null || distant == null) return;

            try {
                Method createTextures = innerLod.getClass().getDeclaredMethod(
                        "createTextures",
                        Class.forName("com.wurmonline.client.game.NearTerrainDataBuffer", false, HookManager.getInstance().getLoader()),
                        Class.forName("com.wurmonline.client.game.DistantTerrainDataBuffer", false, HookManager.getInstance().getLoader())
                );
                createTextures.setAccessible(true);
                createTextures.invoke(innerLod, near, distant);
            } catch (Throwable ignored) {
            }

            invokeNoArgIfExistsAny(innerLod, "refresh", "finalizeTesselation");
            invokeIfExists(innerLod, "finalizeTerrainTextures", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
            invokeNoArgIfExistsAny(terrainRenderer, "refresh");
            invokeIfExists(terrainRenderer, "finalizeTerrainTextures", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});

            forceSeasonVisualRefresh();
        } catch (Throwable ignored) {
        }
    }

    private static void tryWorldResetOnGlThread() {
        if (!pendingWorldReset) return;

        long now = System.currentTimeMillis();
        if (now - lastWorldResetMs < WORLDRESET_COOLDOWN_MS) {
            pendingWorldReset = false;
            return;
        }

        if (!hasGlContextOnThisThread()) return;

        pendingWorldReset = false;
        lastWorldResetMs = now;

        try {
            Object world = (WurmHelper.hud == null) ? null : WurmHelper.hud.getWorld();
            if (world == null) return;

            Object worldRender = findWorldRenderBestEffort(world);
            if (worldRender == null) return;

            invokeNoArgIfExistsAny(worldRender, "refresh");

            Object cellRenderer = null;
            try {
                cellRenderer = tryInvokeNoArg(worldRender, "getCellRenderer");
            } catch (Throwable ignored) {
                cellRenderer = null;
            }
            if (cellRenderer != null) {
                invokeNoArgIfExistsAny(cellRenderer, "requestCleanup", "setAllDirty", "refresh");
            }

            Object terrainRenderer = null;
            try {
                terrainRenderer = tryInvokeNoArg(worldRender, "getTerrainRenderer");
            } catch (Throwable ignored) {
                terrainRenderer = null;
            }
            if (terrainRenderer != null) {
                invokeNoArgIfExistsAny(terrainRenderer, "refresh");
                invokeIfExists(terrainRenderer, "finalizeTerrainTextures", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
            }

            forceSeasonVisualRefresh();
        } catch (Throwable ignored) {
        }
    }

    private static void forceVegetationHardReset() {
        try {
            try {
                ClassLoader loader = HookManager.getInstance().getLoader();
                Class<?> mrl = Class.forName("com.wurmonline.client.renderer.model.ModelResourceLoader", false, loader);
                Method unload = mrl.getDeclaredMethod("unload");
                unload.setAccessible(true);
                unload.invoke(null);
            } catch (Throwable ignored) {
            }

            Object world = WurmHelper.hud == null ? null : WurmHelper.hud.getWorld();
            if (world == null) return;

            Object decorationRenderer = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.terrain.decorator.DecorationRenderer");
            invokeNoArgIfExistsAny(decorationRenderer, "setAllDirty", "refresh", "invalidate", "rebuild");

            Object surfaceCell = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.cell.SurfaceCell");
            invokeNoArgIfExistsAny(surfaceCell, "refresh", "rebuildTreeVolume", "setAllDirty", "invalidate", "rebuild");
            invokeIfExists(surfaceCell, "setDirty", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});

            Object terrainRenderer = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.terrain.TerrainRenderer");
            Object terrainLod = findFieldValueByTypeName(terrainRenderer, "com.wurmonline.client.renderer.terrain.TerrainLod");
            if (terrainLod == null) terrainLod = findFieldValueByTypeName(world, "com.wurmonline.client.renderer.terrain.TerrainLod");

            invokeNoArgIfExistsAny(terrainLod, "refresh", "setAllDirty", "invalidate", "rebuild", "reset");
            invokeIfExists(terrainLod, "setDirty", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});

            forceSeasonVisualRefresh();
        } catch (Throwable ignored) {
        }
    }

    private static void treeAtlasRefreshNow() {
        long now = System.currentTimeMillis();
        if (now - lastTreeAtlasRefreshMs < TREE_ATLAS_REFRESH_COOLDOWN_MS) return;
        lastTreeAtlasRefreshMs = now;

        try {
            Object world = WurmHelper.hud.getWorld();
            if (world == null) return;

            flushTextureCachesForSeasonChange();

            String appendix = "";
            try {
                Method getSeasonManager = world.getClass().getMethod("getSeasonManager");
                Object sm = getSeasonManager.invoke(world);
                if (sm != null) {
                    Method getSeasonAppendix = sm.getClass().getMethod("getSeasonAppendix");
                    Object v = getSeasonAppendix.invoke(sm);
                    appendix = (v instanceof String) ? (String) v : "";
                }
            } catch (Throwable ignored) {
            }

            String treeKey = "img.terrain.treemap" + appendix;
            String bushKey = "img.terrain.bushmap" + appendix;

            try {
                ClassLoader loader = HookManager.getInstance().getLoader();
                Class<?> rtl = Class.forName("com.wurmonline.client.resources.textures.ResourceTextureLoader", false, loader);
                Method getTexture = rtl.getMethod("getTexture", String.class);

                lastTreeMapTextureObj = getTexture.invoke(null, treeKey);
                lastBushMapTextureObj = getTexture.invoke(null, bushKey);
            } catch (Throwable ignored) {
            }

            forceSeasonVisualRefresh();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "treeAtlasRefreshNow failed", t);
        }
    }

    private void handleTreeAtlasCommand(String[] input) {
        treeAtlasRefreshNow();
    }

    private void handleSeasonCommand(String[] input) {
        if (input == null || input.length == 0) {
            Utils.consolePrint("Usage: bot rnd season <spring|summer|fall|autumn|winter|auto|info>");
            return;
        }

        String arg = input[0] == null ? "" : input[0].trim().toLowerCase(Locale.US);

        if ("info".equals(arg) || "status".equals(arg)) {
            printSeasonStatus();
            return;
        }

        final int oldLock = lockSeasonIndex;
        final FakeSeason oldFake = fakeSeason;

        if ("auto".equals(arg) || "off".equals(arg) || "unlock".equals(arg)) {
            lockSeasonIndex = -1;
            fakeSeason = FakeSeason.AUTO;

            if (oldLock != lockSeasonIndex || oldFake != fakeSeason) {
                resetFakeSeasonTileRemap();
                flushTextureCachesForSeasonChange();
                pendingTerrainTextureCacheClear = true;
                pendingBillboardReload = true;
                pendingLodReset = true;
                pendingWorldReset = true;
                forceVegetationHardReset();
            }

            Utils.consolePrint("Season rendering: AUTO");
            return;
        }

        if ("autumn".equals(arg)) arg = "fall";

        Season[] seasons = Season.values();
        for (int i = 0; i < seasons.length; i++) {
            if (seasons[i].name().equalsIgnoreCase(arg)) {
                lockSeasonIndex = i;
                fakeSeason = (seasons[i] == Season.WINTER) ? FakeSeason.WINTER : FakeSeason.AUTO;

                if (oldLock != lockSeasonIndex || oldFake != fakeSeason) {
                    resetFakeSeasonTileRemap();
                    flushTextureCachesForSeasonChange();
                    pendingTerrainTextureCacheClear = true;
                    pendingBillboardReload = true;
                    pendingLodReset = true;
                    pendingWorldReset = true;
                    forceVegetationHardReset();
                }

                Utils.consolePrint("Season rendering locked to: %s", seasons[i].name());
                return;
            }
        }

        Utils.consolePrint("Unknown season: %s", arg);
    }

    private static void printSeasonStatus() {
        int idx = lockSeasonIndex;
        if (idx < 0) {
            Utils.consolePrint("Season rendering: AUTO");
            return;
        }
        Season[] seasons = Season.values();
        if (idx >= seasons.length) {
            Utils.consolePrint("Season rendering: AUTO");
            lockSeasonIndex = -1;
            return;
        }
        Utils.consolePrint("Season rendering: LOCKED to %s", seasons[idx].name());
    }

    private static void safeRegisterHook(String className, String methodName, String desc, InvocationHandlerFactory factory) {
        try {
            HookManager.getInstance().registerHook(className, methodName, desc, factory);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Hook skipped: " + className + "." + methodName + " " + desc, t);
        }
    }

    private static volatile boolean hooksInstalled = false;

    private static void ensureHooksInstalled() {
        if (hooksInstalled) return;
        synchronized (Render.class) {
            if (hooksInstalled) return;

            try {
                HookManager hm = HookManager.getInstance();

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.weather.Weather",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            if (renderWeather) method.invoke(proxy, args);
                            return null;
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.sky.SkyRenderer",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            if (renderSky) method.invoke(proxy, args);
                            return null;
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainRenderer",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            if (!renderTerrain) return null;

                            inTerrainRenderTl.set(Boolean.TRUE);
                            try {
                                return invokeOriginal(method, proxy, args);
                            } finally {
                                inTerrainRenderTl.remove();
                            }
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.cave.CaveRender",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            if (renderCave) method.invoke(proxy, args);
                            return null;
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.renderer.terrain.AdvancedWaterRenderer",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            if (renderWater) method.invoke(proxy, args);
                            return null;
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.renderer.terrain.BasicWaterRenderer",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            if (renderWater) method.invoke(proxy, args);
                            return null;
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.renderer.particles.ParticleRenderer",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            if (renderParticles) method.invoke(proxy, args);
                            return null;
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.renderer.terrain.decorator.DecorationRenderer",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            if (renderGrass) method.invoke(proxy, args);
                            return null;
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.decorator.DecorationChunk",
                        "updateSprites",
                        "()V",
                        () -> (proxy, method, args) -> {
                            skipRawTypeRemapTl.set(Boolean.TRUE);
                            try {
                                return invokeOriginal(method, proxy, args);
                            } finally {
                                skipRawTypeRemapTl.remove();
                            }
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.renderer.cell.SurfaceCell",
                        "renderTrees",
                        "(Lcom/wurmonline/client/renderer/backend/Queue;Lcom/wurmonline/client/renderer/Frustum;Z)V",
                        () -> (proxy, method, args) -> {
                            if (renderTrees) method.invoke(proxy, args);
                            return null;
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.cell.CellRenderer",
                        "prepareRender",
                        "()V",
                        () -> (proxy, method, args) -> {
                            try {
                                lastCellRendererRef = new WeakReference<Object>(proxy);
                            } catch (Throwable ignored) {
                            }
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getTexture",
                        "(Ljava/lang/String;)Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getTexture$1",
                        "(Ljava/lang/String;)Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getNearestTexture",
                        "(Ljava/lang/String;)Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getNearestTextureNonScaling",
                        "(Ljava/lang/String;)Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getNowrapLinearTexture",
                        "(Ljava/lang/String;)Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getNowrapMipmapNearestTexture",
                        "(Ljava/lang/String;)Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getResidentTexture",
                        "(Ljava/lang/String;Z)Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getResidentTexture",
                        "(Ljava/lang/String;Lcom/wurmonline/client/resources/textures/TextureLoader$Filter;ZZ)Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.resources.textures.ResourceTextureLoader",
                        "getInternalTexture",
                        "(ZLjava/lang/String;Lcom/wurmonline/client/resources/textures/TextureLoader$Filter;ZZZ)" +
                                "Lcom/wurmonline/client/resources/textures/ResourceTexture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapKeysInPlace(args, 1);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainTexture",
                        "execute",
                        "(Ljava/lang/Object;)V",
                        () -> (proxy, method, args) -> {
                            inTerrainTextureTl.set(Boolean.TRUE);
                            try {
                                return invokeOriginal(method, proxy, args);
                            } finally {
                                inTerrainTextureTl.remove();
                            }
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainTexture",
                        "doRender",
                        "()V",
                        () -> (proxy, method, args) -> {
                            inTerrainTextureTl.set(Boolean.TRUE);
                            try {
                                return invokeOriginal(method, proxy, args);
                            } finally {
                                inTerrainTextureTl.remove();
                            }
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainTexture",
                        "getTileType",
                        "(II)Lcom/wurmonline/mesh/Tiles$Tile;",
                        () -> (proxy, method, args) -> {
                            Object orig = invokeOriginal(method, proxy, args);
                            return remapTileObjForTerrainTexturing(orig);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainTexture",
                        "getSecondaryTileType",
                        "(II)Lcom/wurmonline/mesh/Tiles$Tile;",
                        () -> (proxy, method, args) -> {
                            Object orig = invokeOriginal(method, proxy, args);
                            return remapTileObjForTerrainTexturing(orig);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainTexture",
                        "getTransitionTile",
                        "(II)Lcom/wurmonline/mesh/Tiles$Tile;",
                        () -> (proxy, method, args) -> {
                            Object orig = invokeOriginal(method, proxy, args);
                            return remapTileObjForTerrainTexturing(orig);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainTexture",
                        "getTileTexture",
                        "(Lcom/wurmonline/client/game/World;Lcom/wurmonline/mesh/Tiles$Tile;)" +
                                "Lcom/wurmonline/client/resources/textures/Texture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapTileArgForTerrainTexturing(args, 1);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainTexture",
                        "getTileProperties",
                        "(Lcom/wurmonline/mesh/Tiles$Tile;)" +
                                "Lcom/wurmonline/client/renderer/terrain/TilePropertiesXml$TileProperties;",
                        () -> (proxy, method, args) -> {
                            maybeRemapTileArgForTerrainTexturing(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.renderer.terrain.TerrainTexture",
                        "getTileNormalMap",
                        "(Lcom/wurmonline/mesh/Tiles$Tile;)" +
                                "Lcom/wurmonline/client/resources/textures/Texture;",
                        () -> (proxy, method, args) -> {
                            maybeRemapTileArgForTerrainTexturing(args, 0);
                            return invokeOriginal(method, proxy, args);
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.game.SeasonManager",
                        "selectSeason",
                        "(FF)Lcom/wurmonline/client/game/SeasonManager$Season;",
                        () -> (proxy, method, args) -> {
                            final int idx = lockSeasonIndex;
                            if (idx >= 0) {
                                Season[] seasons = Season.values();
                                if (idx < seasons.length) return seasons[idx];
                            }
                            return method.invoke(proxy, args);
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.game.SeasonManager",
                        "getSeason",
                        "()Lcom/wurmonline/client/game/SeasonManager$Season;",
                        () -> (proxy, method, args) -> {
                            final int idx = lockSeasonIndex;
                            if (idx >= 0) {
                                Season[] seasons = Season.values();
                                if (idx < seasons.length) return seasons[idx];
                            }
                            return method.invoke(proxy, args);
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.game.SeasonManager",
                        "getSeasonAppendix",
                        "()Ljava/lang/String;",
                        () -> (proxy, method, args) -> {
                            final int idx = lockSeasonIndex;
                            if (idx >= 0) {
                                return forcedSeasonAppendixOrEmpty();
                            }
                            Object v = method.invoke(proxy, args);
                            return (v instanceof String) ? (String) v : "";
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.game.TerrainDataBuffer",
                        "getRawTypeInternal",
                        "(II)B",
                        () -> (proxy, method, args) -> {
                            byte raw = ((Byte) method.invoke(proxy, args)).byteValue();
                            return Byte.valueOf(remapRawTypeForFakeSeason(raw));
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.game.TerrainDataBuffer",
                        "getTileType",
                        "(II)Lcom/wurmonline/mesh/Tiles$Tile;",
                        () -> (proxy, method, args) -> {
                            Object orig = method.invoke(proxy, args);
                            if (fakeSeason != FakeSeason.WINTER) return orig;

                            Boolean inTerrain = inTerrainRenderTl.get();
                            if (inTerrain == null || !inTerrain.booleanValue()) return orig;

                            if (looksLikeTreeOrBushTile(orig)) return orig;

                            ensureTileMapInitialized();
                            if ((TILE_SNOW_ID & 0xFF) == 0xFF) return orig;

                            byte origId = tryGetTileIdFromTileObj(orig);
                            if (origId == (byte) -1) return orig;

                            if (winterRemapFrom.contains(Byte.valueOf(origId))) {
                                Object snow = TILE_SNOW_TILE_OBJ;
                                return (snow != null) ? snow : orig;
                            }

                            return orig;
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.game.NearTerrainDataBuffer",
                        "getRawType",
                        "(II)B",
                        () -> (proxy, method, args) -> {
                            byte raw = ((Byte) method.invoke(proxy, args)).byteValue();
                            return Byte.valueOf(remapRawTypeForFakeSeason(raw));
                        }
                );

                hm.registerHook(
                        "com.wurmonline.client.game.DistantTerrainDataBuffer",
                        "getRawType",
                        "(II)B",
                        () -> (proxy, method, args) -> {
                            byte raw = ((Byte) method.invoke(proxy, args)).byteValue();
                            return Byte.valueOf(remapRawTypeForFakeSeason(raw));
                        }
                );

                safeRegisterHook(
                        "com.wurmonline.client.WurmClientBase",
                        "renderFrame",
                        "()V",
                        () -> (proxy, method, args) -> {
                            try {
                                if (pendingTerrainTextureCacheClear) {
                                    pendingTerrainTextureCacheClear = false;
                                    clearTerrainTextureStaticCaches();
                                }
                            } catch (Throwable ignored) {
                            }

                            try { tryWorldResetOnGlThread(); } catch (Throwable ignored) {}
                            try { tryReloadBillboardTexturesOnGlThread(); } catch (Throwable ignored) {}
                            try { tryLodResetOnGlThread(); } catch (Throwable ignored) {}

                            // Actively watch for season changes in AUTO mode
                            try { maybeAutoRefreshOnSeasonChangeActive(); } catch (Throwable ignored) {}

                            return invokeOriginal(method, proxy, args);
                        }
                );

                hooksInstalled = true;
                logger.log(Level.INFO, "Render bot hooks installed.");
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Failed to install Render bot hooks", t);
                Utils.consolePrint("Render bot failed to install hooks.");
            }
        }
    }

    public static void installHooksEarly() {
        ensureHooksInstalled();
    }

    private static void toggle(Key k) {
        switch (k) {
            case weather:
                renderWeather = !renderWeather;
                break;
            case sky:
                renderSky = !renderSky;
                break;
            case terrain:
                renderTerrain = !renderTerrain;
                break;
            case cave:
                renderCave = !renderCave;
                break;
            case water:
                renderWater = !renderWater;
                break;
            case particles:
                renderParticles = !renderParticles;
                break;
            case grass:
                renderGrass = !renderGrass;
                break;
            case trees:
                renderTrees = !renderTrees;
                break;
            default:
                break;
        }
        Utils.consolePrint("Render toggles updated.");
    }

    private static void toggleAll() {
        boolean newValue = !(renderWeather && renderSky && renderTerrain && renderCave && renderWater && renderParticles && renderGrass && renderTrees);
        renderWeather = newValue;
        renderSky = newValue;
        renderTerrain = newValue;
        renderCave = newValue;
        renderWater = newValue;
        renderParticles = newValue;
        renderGrass = newValue;
        renderTrees = newValue;
        Utils.consolePrint("Render toggles updated.");
    }

    private void printRenderHelp() {
        Utils.consolePrint("==== Render commands ====");
        Utils.consolePrint("Usage: bot rnd <command> [args]");
        for (Key k : Key.values()) {
            String usage = k.getUsage();
            String usageSuffix = (usage == null || usage.trim().isEmpty()) ? "" : " " + usage.trim();
            Utils.consolePrint(" - %s%s : %s", k.getName(), usageSuffix, k.getDescription());
        }
    }

    public Render() {
        ensureHooksInstalled();

        registerInputHandler(Key.weather, _in -> toggle(Key.weather));
        registerInputHandler(Key.sky, _in -> toggle(Key.sky));
        registerInputHandler(Key.terrain, _in -> toggle(Key.terrain));
        registerInputHandler(Key.cave, _in -> toggle(Key.cave));
        registerInputHandler(Key.water, _in -> toggle(Key.water));
        registerInputHandler(Key.particles, _in -> toggle(Key.particles));
        registerInputHandler(Key.grass, _in -> toggle(Key.grass));
        registerInputHandler(Key.trees, _in -> toggle(Key.trees));
        registerInputHandler(Key.all, _in -> toggleAll());

        registerInputHandler(Key.season, this::handleSeasonCommand);
        registerInputHandler(Key.treeatlas, this::handleTreeAtlasCommand);
        registerInputHandler(Key.help, _in -> printRenderHelp());
    }

    @Override
    void work() throws Exception {
        ensureHooksInstalled();
        Utils.consolePrint("Render bot is running. Use: bot rnd help");
        while (isActive()) {
            waitOnPause();
            Thread.sleep(timeout);
        }
    }

    private enum Key implements InputKey {
        weather("Toggle weather rendering", ""),
        sky("Toggle sky rendering", ""),
        terrain("Toggle terrain rendering", ""),
        cave("Toggle cave rendering", ""),
        water("Toggle water rendering", ""),
        particles("Toggle particle rendering", ""),
        grass("Toggle grass/decoration rendering", ""),
        trees("Toggle tree rendering", ""),
        all("Toggle all render flags at once", ""),
        season("Set season (locks SeasonManager). Winter also enables terrain snow remap/refresh.", "<spring|summer|autumn|winter|auto|info>"),
        treeatlas("Refresh tree/bush atlas textures (safe refresh; no full GL rebuild).", ""),
        help("Show this help in the console", "");

        private final String description;
        private final String usage;

        Key(String description, String usage) {
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

    // Removed: DEFAULT_GAME_GRAPHICS_JAR and openGraphicsJarStream()
}