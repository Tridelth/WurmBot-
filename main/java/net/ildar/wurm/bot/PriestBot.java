package net.ildar.wurm.bot;

import com.wurmonline.client.comm.SimpleServerConnectionClass;
import com.wurmonline.client.game.PlayerObj;
import com.wurmonline.client.game.SpellEffect;
import com.wurmonline.client.game.World;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.client.renderer.gui.InventoryListComponent;
import com.wurmonline.client.renderer.gui.InventoryWindow;
import com.wurmonline.client.renderer.gui.ItemListWindow;
import com.wurmonline.client.renderer.gui.PaperDollInventory;
import com.wurmonline.client.renderer.gui.PaperDollSlot;
import com.wurmonline.client.renderer.gui.WurmComponent;
import com.wurmonline.shared.constants.PlayerAction;
import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;


@BotInfo(
        description = "This bot does all priestly matters.",
        abbreviation = "priest"
)
public class PriestBot extends Bot {
    private boolean enabled = false;
    private boolean debug = false;

    // Controls whether act-id learning prints anything (learning still happens regardless)
    private static volatile boolean observeActionDebug = false;

    /**
     * Remembers the last spell selected via `bot priest spell ...` / `bot priest use ...` (when it matches a spell),
     * for the lifetime of the client session.
     */
    private static CastSpell lastSelectedSpell = CastSpell.WOA;
    // NEW: if we keep timing out, back off (and optionally stop skiller) instead of hammering forever
    private int skillerConsecutiveTimeouts = 0;
    // Cache: lowercased spell list name -> resolved server action id
    private final Map<String, Short> resolvedSpellActionIds = new HashMap<>();

    // ------------------------------
    // Favor auto-calibration (server-specific)
    // ------------------------------

    private final EnumMap<CastSpell, Integer> learnedFavorOffset = new EnumMap<>(CastSpell.class);
    private static final float FAVOR_TOO_MUCH_MARGIN = 10.0f;

    /**
     * Track the last target we attempted to cast on (item/ground).
     * Used for "shatters!" removal and other per-target reactions.
     */
    private volatile long lastCastTargetId = 0L;

    /**
     * Blacklist of target ids that should never be enchanted again (session-only),
     * e.g. after "shatters!" or similar destruction events.
     */
    private final Set<Long> brokenTargetIds = new HashSet<>();

    private void markTargetBroken(long id, String reason) {
        if (id <= 0) return;

        brokenTargetIds.add(id);

        // If it was a manual ground target, remove it from that list too.
        String removed = manualGroundTargets.remove(id);

        // Cleanup caches/queues so we don't keep examining a dead id.
        examinedPowerCache.remove(id);
        lastExamineRequestMs.remove(id);
        final long targetId = id;
        examineQueue.removeIf(q -> q == targetId);

        Utils.consolePrint(
                "Target removed from enchanting list (id=%d)%s%s",
                id,
                (removed != null ? " name=" + removed : ""),
                (reason == null || reason.trim().isEmpty()) ? "" : (" reason=" + reason)
        );
    }

    private boolean isTargetBroken(long id) {
        return id > 0 && brokenTargetIds.contains(id);
    }


    private int getLearnedFavorOffset(CastSpell s) {
        if (s == null) return 0;
        Integer v = learnedFavorOffset.get(s);
        return (v == null) ? 0 : v;
    }

    private int getFavorRequirement(CastSpell s) {
        if (s == null) return 0;
        int base = Math.max(0, s.favorCap);
        int off = Math.max(0, getLearnedFavorOffset(s));
        return base + off;
    }

    private void bumpFavorRequirementUp(CastSpell s, String reason) {
        if (s == null) return;
        int oldOff = getLearnedFavorOffset(s);
        int newOff = oldOff + 1;
        learnedFavorOffset.put(s, newOff);

        Utils.consolePrint(
                "Favor calibration: %s requirement increased to >=%d (base=%d offset=+%d)%s",
                s.abbr,
                getFavorRequirement(s),
                s.favorCap,
                newOff,
                (reason == null || reason.trim().isEmpty()) ? "" : (" [" + reason + "]")
        );
    }

    private void maybeBumpFavorRequirementDownAfterSuccess(CastSpell s) {
        if (s == null) return;

        float favorLeft = getCurrentFavor();
        if (favorLeft < 0f) return; // unknown -> don't adjust

        int req = getFavorRequirement(s);
        if (favorLeft > (req + FAVOR_TOO_MUCH_MARGIN)) {
            int oldOff = getLearnedFavorOffset(s);
            if (oldOff <= 0) return;

            int newOff = oldOff - 1;
            learnedFavorOffset.put(s, newOff);

            Utils.consolePrint(
                    "Favor calibration: %s requirement decreased to >=%d (base=%d offset=+%d). Favor left=%.2f",
                    s.abbr,
                    getFavorRequirement(s),
                    s.favorCap,
                    newOff,
                    favorLeft
            );
        }
    }

    /**
     * Rate-limit for repeated debug skip messages (spell+item+conflict).
     */
    private static final long DBG_CONFLICT_THROTTLE_MS = 10_000;
    private final Map<String, Long> dbgConflictLastPrintMs = new HashMap<>();

    /**
     * Windows to read selected items from.
     */
    private final List<InventoryListComponent> targetWindows = new ArrayList<>();

    /**
     * Which enchant we are currently comparing/sorting by.
     */
    private String activeEnchantKey = BuiltInEnchant.WOA.key;

    /**
     * Configured enchants to look for.
     */
    private final LinkedHashMap<String, EnchantCheck> enchants = new LinkedHashMap<>();

    // ------------------------------
    // Manual ground targets (pinned)
    // ------------------------------

    /**
     * World objects added via 'bot priest additem' (id -> best-effort hover name).
     */
    private final LinkedHashMap<Long, String> manualGroundTargets = new LinkedHashMap<>();

    // ------------------------------
    // Casting
    // ------------------------------

    private boolean castingEnabled = false;

    // Casting pacing: prevent action spam during cast times (item/ground mode)
    private boolean castingCastInFlight = false;
    private long castingNextCastAllowedMs = 0L;

    // Failsafe so casting doesn't get stuck forever if we miss the "cast finished" message
    private long castingCastSentAtMs = 0L;
    private static final long CASTING_INFLIGHT_TIMEOUT_MS = 20_000L;

    // NEW: progress-bar based completion (chat-independent)
    private boolean castingSawProgressThisCast = false;

    /**
     * Skiller mode: casts selected spell on player's body.
     * ONLY these spells are allowed in skiller mode:
     * - ltoken (Light Token)
     * - bless  (Bless)
     * - dispel (Dispel)
     * - wov    (Wisdom of Vynora)
     */
    private boolean skillerEnabled = false;
    private long bodyId = 0;
    private CastSpell activeSpell = lastSelectedSpell;
    private long statuetteId = 0;

    // Skiller pacing: prevent action spam during cast times
    private boolean skillerCastInFlight = false;
    private long skillerNextCastAllowedMs = 0L;

    // Failsafe so skiller doesn't get stuck forever if we miss the "cast finished" message
    private long skillerCastSentAtMs = 0L;
    private static final long SKILLER_INFLIGHT_TIMEOUT_MS = 15_000L;

    // NEW: progress-bar based completion (chat-independent)
    private boolean skillerSawProgressThisCast = false;

    // NEW: tune skiller pacing (reduce the visible pause)
    private static final long SKILLER_POST_FINISH_DELAY_MS = 150L;     // tiny delay after action completes
    private static final long SKILLER_MIN_INTERVAL_MS = 900L;          // general minimum between sends
    private static final long SKILLER_LIGHT_TOKEN_MIN_INTERVAL_MS = 2000L; // Light Token is slower on most servers

    // ------------------------------
    // Praying (repeating batches, altar under cursor at command time)
    // ------------------------------
    private boolean praying = false;
    private long prayAltarId = 0L;
    private long prayTimeoutMs = 1_230_000L; // default ~20.5 minutes
    private int prayBatchCount = 1;          // how many PRAY actions to send each cycle
    private long nextPrayAtMs = 0L;

    // NEW: pray batching / queue-limit handling
    private static final long PRAY_ACTION_DURATION_MS = 12_900L;   // each pray action ~15s
    private static final long PRAY_QUEUE_BUFFER_MS = 350L;         // small buffer so we don't hit edge timing
    private static final long PRAY_BUSY_BACKOFF_MS = 2_000L;       // if "You're too busy." -> wait a bit
    private int prayRemainingInCycle = 0;
    private long prayNextChunkAtMs = 0L;

    // Throttle "No target windows" console spam (compare mode only)
    private static final long NO_TARGET_WINDOWS_THROTTLE_MS = 10_000L;
    private long lastNoTargetWindowsPrintMs = 0L;
    private boolean successfulCastStart;
    private boolean successfulCasting;

    // ------------------------------
    // EXAMINE learning / caching
    // ------------------------------

    private final Map<Long, Map<String, Double>> examinedPowerCache = new HashMap<>();
    private final Map<Long, Long> lastExamineRequestMs = new HashMap<>();
    private final ArrayDeque<Long> examineQueue = new ArrayDeque<>();
    private static final long EXAMINE_THROTTLE_MS = 1200;
    private static final long EXAMINE_WINDOW_MS = 2500;
    private long pendingExamineItemId = 0;
    private long pendingExamineUntilMs = 0;
    private boolean pendingExamineSawAnyLine = false;

    // Cooldowns (some spells require waiting between casts)
    private final EnumMap<CastSpell, Long> spellCooldownUntilMs = new EnumMap<>(CastSpell.class);
    private long lastCooldownPrintMs = 0;

    private final Set<String> pendingFoundEnchantKeys = new HashSet<>();

    // Favor warning throttle (prevents console spam)
    private static final long FAVOR_WARN_THROTTLE_MS = 5_000L;
    private long lastFavorWarnMs = 0L;

    public PriestBot() {
        addBuiltIn(BuiltInEnchant.WOA);
        addBuiltIn(BuiltInEnchant.COC);
        addBuiltIn(BuiltInEnchant.BOTD);
        addBuiltIn(BuiltInEnchant.LT);
        addBuiltIn(BuiltInEnchant.HARDEN);
        addBuiltIn(BuiltInEnchant.VEN);
        addBuiltIn(BuiltInEnchant.TITANFORGED);
        addBuiltIn(BuiltInEnchant.EFFICIENCY);
        addBuiltIn(BuiltInEnchant.BAG_OF_HOLDING);
        addBuiltIn(BuiltInEnchant.EXPAND);
        addBuiltIn(BuiltInEnchant.PHASING);
        addBuiltIn(BuiltInEnchant.ROTTING_TOUCH);
        addBuiltIn(BuiltInEnchant.BLOODTHIRST);
        addBuiltIn(BuiltInEnchant.FROSTBRAND);
        addBuiltIn(BuiltInEnchant.FLAMING_AURA);
        addBuiltIn(BuiltInEnchant.AURA_OF_SHARED_PAIN);
        addBuiltIn(BuiltInEnchant.WEB_ARMOUR);
        addBuiltIn(BuiltInEnchant.DIRT);
        addBuiltIn(BuiltInEnchant.MIND_STEALER);
        addBuiltIn(BuiltInEnchant.NOLOCATE);
        addBuiltIn(BuiltInEnchant.VESSEL);
        addBuiltIn(BuiltInEnchant.NIMBLENESS);

        // Keep compare key aligned to remembered spell on bot creation (only if spell maps to an enchant)
        if (activeSpell.enchantKey != null && enchants.containsKey(activeSpell.enchantKey)) {
            activeEnchantKey = activeSpell.enchantKey;
        }

        registerInputHandler(InputKey.on, _in -> toggleOn());
        registerInputHandler(InputKey.cast, _in -> toggleCasting());
        registerInputHandler(InputKey.addwin, _in -> addTargetWindow());
        registerInputHandler(InputKey.clearwins, _in -> clearTargetWindows());

        registerInputHandler(InputKey.list, _in -> listConfiguredEnchants());
        // "use" accepts either an enchant key OR a spell abbreviation (then it behaves like `spell`)
        registerInputHandler(InputKey.use, this::setActiveEnchant);

        registerInputHandler(InputKey.scan, _in -> scanOnceAndPrint());
        registerInputHandler(InputKey.debug, _in -> toggleDebug());

        // Manual ground targets
        registerInputHandler(InputKey.additem, _in -> addGroundItemUnderCursor());
        registerInputHandler(InputKey.itemremove, _in -> removeGroundItemUnderCursor());

        // Casting controls (startcast merged into start)
        registerInputHandler(InputKey.skiller, _in -> toggleSkiller());
        registerInputHandler(InputKey.spell, this::setSpell);

        // Praying controls
        registerInputHandler(InputKey.p, this::togglePraying);
        registerInputHandler(InputKey.pt, this::setPrayTimeout);

        // Help
        registerInputHandler(InputKey.help, _in -> printPriestHelp());
    }

    /**
     * Prints a concise "bot priest help" list to the console.
     */
    private void printPriestHelp() {
        Utils.consolePrint("==== PriestBot commands ====");
        Utils.consolePrint("Usage: bot priest <command> [args]");
        Utils.consolePrint("");

        InputKey[] keys = new InputKey[]{
                InputKey.on,
                InputKey.addwin,
                InputKey.clearwins,

                InputKey.list,
                InputKey.use,

                InputKey.scan,
                InputKey.debug,

                InputKey.additem,
                InputKey.itemremove,

                InputKey.skiller,
                InputKey.spell,

                InputKey.p,
                InputKey.pt,
                InputKey.help
        };

        for (InputKey k : keys) {
            if (k == null) continue;

            String usage = k.getUsage();
            String usageSuffix = (usage == null || usage.trim().isEmpty()) ? "" : " " + usage.trim();

            Utils.consolePrint(" - %s%s : %s", k.getName(), usageSuffix, k.getDescription());
        }

        Utils.consolePrint("");
        Utils.consolePrint("Tip: \"bot p\" is an alias for \"bot priest\".");
    }

    @Override
    public void work() throws Exception {
        registerEventProcessors();
        setTimeout(300);

        World world = WurmHelper.hud.getWorld();
        PlayerObj player = world.getPlayer();
        SimpleServerConnectionClass serverConnection = world.getServerConnection();

        while (isActive()) {
            waitOnPause();

            finalizeExpiredPendingExamineIfNeeded();

            if (!enabled) {
                sleep(timeout);
                continue;
            }

            // NEW: prefer progress-bar completion over chat (immune to other players' messages)
            // Skiller
            // NEW: prefer progress-bar completion over chat (immune to other players' messages)
            if (skillerEnabled && skillerCastInFlight) {
                Float p = tryGetActionProgress();
                if (p != null) {
                    if (p > 0f) skillerSawProgressThisCast = true;
                    if (skillerSawProgressThisCast && p == 0f) {
                        skillerCastInFlight = false;
                        skillerCastSentAtMs = 0L;
                        skillerSawProgressThisCast = false;

                        long now = System.currentTimeMillis();
                        skillerNextCastAllowedMs = Math.max(skillerNextCastAllowedMs, now + SKILLER_POST_FINISH_DELAY_MS);

                        if (debug) Utils.consolePrint("DBG skiller: progress->0 (action finished) -> cleared");
                    }
                }
            }

            // Item/ground casting
            if (castingEnabled && castingCastInFlight) {
                Float p = tryGetActionProgress();
                if (p != null) {
                    if (p > 0f) castingSawProgressThisCast = true;
                    if (castingSawProgressThisCast && p == 0f) {
                        castingCastInFlight = false;
                        castingCastSentAtMs = 0L;
                        castingSawProgressThisCast = false;
                        castingNextCastAllowedMs = Math.max(castingNextCastAllowedMs, System.currentTimeMillis() + 350L);
                        if (debug) Utils.consolePrint("DBG casting: progress->0 (action finished) -> cleared");
                    }
                }
            }

            // Casting in-flight timeout failsafe (item/ground mode)
            if (castingEnabled && castingCastInFlight && castingCastSentAtMs > 0L) {
                long now = System.currentTimeMillis();
                if (now - castingCastSentAtMs > CASTING_INFLIGHT_TIMEOUT_MS) {
                    castingCastInFlight = false;
                    castingCastSentAtMs = 0L;
                    if (debug) Utils.consolePrint("DBG casting: in-flight timeout -> cleared");
                }
            }

            boolean pumped = pumpExamineQueue();
            if (pumped) {
                sleep(timeout);
                continue;
            }

            // -------------------------
            // Repeating praying (batch)
            // -------------------------
            if (praying && prayAltarId > 0) {
                long now = System.currentTimeMillis();

                // Start a new pray cycle when timeout elapses (or first run)
                if (prayRemainingInCycle <= 0 && (nextPrayAtMs == 0L || now >= nextPrayAtMs)) {
                    prayRemainingInCycle = Math.max(1, prayBatchCount);
                    prayNextChunkAtMs = now;
                }

                // Send chunks limited by max queue size
                if (prayRemainingInCycle > 0 && now >= prayNextChunkAtMs) {
                    int maxQueue = getMaxQueuedActions();
                    int chunk = Math.max(1, Math.min(maxQueue, prayRemainingInCycle));

                    for (int i = 0; i < chunk; i++) {
                        WurmHelper.hud.sendAction(PlayerAction.PRAY, prayAltarId);
                    }

                    prayRemainingInCycle -= chunk;

                    if (prayRemainingInCycle > 0) {
                        // IMPORTANT:
                        // If we queued `chunk` prays, the queue will be busy for roughly `chunk * 15s`.
                        // Wait for the whole chunk to play out before sending more.
                        prayNextChunkAtMs = now + (chunk * PRAY_ACTION_DURATION_MS) + PRAY_QUEUE_BUFFER_MS;
                    } else {
                        // Cycle complete -> schedule next cycle
                        nextPrayAtMs = now + prayTimeoutMs;
                        prayNextChunkAtMs = 0L;
                    }
                }
            }

            // -------------------------
            // Skiller mode (cast on body)
            // -------------------------
            if (skillerEnabled) {
                if (statuetteId <= 0 && !ensureStatuette()) {
                    sleep(timeout);
                    continue;
                }
                if (bodyId <= 0 && !ensureBody()) {
                    sleep(timeout);
                    continue;
                }

                refreshActiveSpellActionIdIfNeeded();

                long now = System.currentTimeMillis();

                if (skillerCastInFlight || now < skillerNextCastAllowedMs) {
                    sleep(timeout);
                    continue;
                }

                // Favor gate (prevents "not enough favor" spam)
                float favor = getCurrentFavor();
                int reqFavor = getFavorRequirement(activeSpell);
                if (favor >= 0f && favor < reqFavor) {
                    if (now - lastFavorWarnMs >= FAVOR_WARN_THROTTLE_MS) {
                        lastFavorWarnMs = now;
                        Utils.consolePrint(
                                "Not enough favor for %s (need >=%d, have %.2f). Waiting...",
                                activeSpell.abbr, reqFavor, favor
                        );
                    }
                    sleep(timeout);
                    continue;
                }

                long cdUntil = spellCooldownUntilMs.getOrDefault(activeSpell, 0L);
                if (cdUntil > now) {
                    if (now - lastCooldownPrintMs > 5000) {
                        lastCooldownPrintMs = now;
                        Utils.consolePrint("Cooldown active for %s (%d sec left)", activeSpell.abbr, Math.max(0, (cdUntil - now) / 1000));
                    }
                    sleep(timeout);
                    continue;
                }

                successfulCastStart = false;
                successfulCasting = false;

                serverConnection.sendAction(
                        statuetteId,
                        new long[]{bodyId},
                        activeSpell.getPlayerAction()
                );

                skillerCastInFlight = true;
                skillerCastSentAtMs = now;
                skillerSawProgressThisCast = false;

                long minIntervalMs = (activeSpell == CastSpell.LIGHT_TOKEN)
                        ? SKILLER_LIGHT_TOKEN_MIN_INTERVAL_MS
                        : SKILLER_MIN_INTERVAL_MS;
                skillerNextCastAllowedMs = now + minIntervalMs;

                sleep(timeout);
                continue;
            }

            // -------------------------
            // Item/ground casting mode
            // -------------------------
            if (castingEnabled) {
                EnchantCheck activeEnchant = enchants.get(activeEnchantKey);
                if (activeEnchant == null) {
                    sleep(timeout);
                    continue;
                }

                // NEW: ensure dynamic act_id is resolved before cast
                refreshActiveSpellActionIdIfNeeded();

                long now = System.currentTimeMillis();
                if (castingCastInFlight || now < castingNextCastAllowedMs) {
                    sleep(timeout);
                    continue;
                }

                // Prefer manual ground targets when present, else selected items from windows
                if (!manualGroundTargets.isEmpty()) {
                    queueExaminesForTargetIds(manualGroundTargets.keySet());

                    Long bestId = pickBestTargetIdToCastOn(manualGroundTargets.keySet(), activeEnchant);
                    if (bestId == null) {
                        sleep(timeout);
                        continue;
                    }

                    if (statuetteId <= 0 && !ensureStatuette()) {
                        sleep(timeout);
                        continue;
                    }

                    // Favor gate (prevents "not enough favor" spam)
                    float favor = getCurrentFavor();
                    if (favor >= 0f && favor < activeSpell.favorCap) {
                        if (now - lastFavorWarnMs >= FAVOR_WARN_THROTTLE_MS) {
                            lastFavorWarnMs = now;
                            Utils.consolePrint(
                                    "Not enough favor for %s (need >=%d, have %.2f). Waiting...",
                                    activeSpell.abbr, activeSpell.favorCap, favor
                            );
                        }
                        sleep(timeout);
                        continue;
                    }

                    long cdUntil = spellCooldownUntilMs.getOrDefault(activeSpell, 0L);
                    if (cdUntil > now) {
                        if (now - lastCooldownPrintMs > 5000) {
                            lastCooldownPrintMs = now;
                            Utils.consolePrint("Cooldown active for %s (%d sec left)", activeSpell.abbr, Math.max(0, (cdUntil - now) / 1000));
                        }
                        sleep(timeout);
                        continue;
                    }

                    serverConnection.sendAction(
                            statuetteId,
                            new long[]{bestId},
                            activeSpell.getPlayerAction()
                    );

                    lastCastTargetId = bestId;

                    castingCastInFlight = true;
                    castingCastSentAtMs = now;
                    castingSawProgressThisCast = false;

                    // Conservative default spacing; actual gating is "cast finished" event.
                    castingNextCastAllowedMs = now + 750L;

                    sleep(timeout);
                    continue;
                } else {
                    List<InventoryMetaItem> selected = getSelectedItemsFromWindows();
                    if (selected.isEmpty()) {
                        sleep(timeout);
                        continue;
                    }

                    queueExaminesForSelectedItems(selected);

                    InventoryMetaItem best = pickBestItemToCastOn(selected, activeEnchant);
                    if (best == null) {
                        sleep(timeout);
                        continue;
                    }

                    if (statuetteId <= 0 && !ensureStatuette()) {
                        sleep(timeout);
                        continue;
                    }

                    // Favor gate (prevents "not enough favor" spam)
                    float favor = getCurrentFavor();
                    int reqFavor = getFavorRequirement(activeSpell);
                    if (favor >= 0f && favor < reqFavor) {
                        if (now - lastFavorWarnMs >= FAVOR_WARN_THROTTLE_MS) {
                            lastFavorWarnMs = now;
                            Utils.consolePrint(
                                    "Not enough favor for %s (need >=%d, have %.2f). Waiting...",
                                    activeSpell.abbr, reqFavor, favor
                            );
                        }
                        sleep(timeout);
                        continue;
                    }

                    long cdUntil = spellCooldownUntilMs.getOrDefault(activeSpell, 0L);
                    if (cdUntil > now) {
                        if (now - lastCooldownPrintMs > 5000) {
                            lastCooldownPrintMs = now;
                            Utils.consolePrint("Cooldown active for %s (%d sec left)", activeSpell.abbr, Math.max(0, (cdUntil - now) / 1000));
                        }
                        sleep(timeout);
                        continue;
                    }

                    successfulCastStart = false;
                    successfulCasting = false;

                    serverConnection.sendAction(
                            statuetteId,
                            new long[]{best.getId()},
                            activeSpell.getPlayerAction()
                    );

                    lastCastTargetId = best.getId();

                    castingCastInFlight = true;
                    castingCastSentAtMs = now;
                    castingSawProgressThisCast = false;

                    // Conservative default spacing; actual gating is "cast finished" event.
                    castingNextCastAllowedMs = now + 750L;

                    sleep(timeout);
                    continue;
                }
            }

            boolean prayOnlyNoTargets =
                    praying
                            && !castingEnabled
                            && !skillerEnabled
                            && targetWindows.isEmpty()
                            && manualGroundTargets.isEmpty();

            if (!prayOnlyNoTargets) {
                scanOnceAndPrint();
                sleep(2000);
            } else {
                sleep(timeout);
            }
        }
    }

    // -------------------------
    // Pray command handlers
    // -------------------------
    private void togglePraying(String[] input) {
        if (praying) {
            praying = false;
            nextPrayAtMs = 0L;
            Utils.consolePrint("Priest praying is OFF.");
            return;
        }

        int batch = 1;
        if (input != null && input.length >= 1 && input[0] != null && !input[0].trim().isEmpty()) {
            try {
                batch = Integer.parseInt(input[0].trim());
            } catch (Exception e) {
                printInputKeyUsageString(InputKey.p);
                return;
            }
        }
        if (batch <= 0) {
            Utils.consolePrint("Batch count must be >= 1");
            return;
        }

        final boolean wasEnabled = enabled;

        Long altarId = tryGetAltarIdUnderCursor();
        if (altarId == null || altarId <= 0) {
            Utils.consolePrint("Can't find an altar under cursor.");
            enabled = wasEnabled;
            return;
        }

        prayAltarId = altarId;
        prayBatchCount = batch;

        praying = true;
        nextPrayAtMs = 0L;

        if (!enabled) {
            enabled = true;
            Utils.consolePrint("PriestBot enabled=true (auto-enabled by p)");
        }

        Utils.consolePrint(
                "Priest praying is ON. altarId=%d batch=%d every(pt)=%d ms. Type \"bot priest p\" again to stop.",
                prayAltarId, prayBatchCount, prayTimeoutMs
        );
    }

    private Long tryGetAltarIdUnderCursor() {
        try {
            World world = WurmHelper.hud.getWorld();
            int x = world.getClient().getXMouse();
            int y = world.getClient().getYMouse();

            long id = 0L;

            long[] targets = WurmHelper.hud.getCommandTargetsFrom(x, y);
            if (targets != null && targets.length > 0 && targets[0] > 0) {
                id = targets[0];
            }

            String hoverName = null;
            try {
                Object hovered = world.getCurrentHoveredObject();
                if (hovered != null) {
                    Method m = hovered.getClass().getMethod("getHoverName");
                    Object v = m.invoke(hovered);
                    if (v instanceof String) hoverName = (String) v;

                    if (id <= 0) {
                        Method mid = hovered.getClass().getMethod("getId");
                        Object vid = mid.invoke(hovered);
                        if (vid instanceof Number) id = ((Number) vid).longValue();
                    }
                }
            } catch (Exception ignored) {
            }

            if (id <= 0) return null;

            if (hoverName != null && !hoverName.toLowerCase(Locale.US).contains("altar")) return null;

            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private void setPrayTimeout(String[] input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(InputKey.pt);
            return;
        }

        try {
            long t = Long.parseLong(input[0]);
            if (t < 100) t = 100;
            prayTimeoutMs = t;
            Utils.consolePrint("Priest pray timeout (pt) is now %d ms", prayTimeoutMs);
        } catch (Exception e) {
            Utils.consolePrint("Wrong timeout value!");
        }
    }

    /**
     * Computes how many actions the player can queue based on Mind Logic.
     * Rules requested:
     *  - mind logic < 20.0 -> 3 actions
     *  - for each +10.0 mind logic at/above 20.0 -> +1 queue slot
     */
    private int getMaxQueuedActionsFromMindLogic() {
        try {
            World world = WurmHelper.hud.getWorld();
            PlayerObj player = world.getPlayer();
            float ml = player.getSkillSet().getSkillValue("mind logic");

            int base = 3;
            if (ml < 20.0f) return base;

            int extra = (int) Math.floor((ml - 20.0f) / 10.0f);
            return Math.max(1, base + Math.max(0, extra));
        } catch (Exception ignored) {
            return 3; // safe default
        }
    }

    /**
     * Try to resolve ALL dynamic spell action ids from the player's spell list.
     * This avoids menu-opening on clients/servers where the spell list already contains action ids.
     */
    private void warmUpDynamicSpellActionIdsFromPlayer() {
        try {
            int resolvedCount = 0;

            for (CastSpell s : CastSpell.values()) {
                if (s == null) continue;
                if (!s.usesDynamicActionId()) continue;

                String key = s.lookupNameLower();
                if (key == null || key.isEmpty()) continue;

                // Skip if already learned/known
                synchronized (observedActionLock) {
                    Short observed = observedActionNameToId.get(key);
                    if (observed != null && observed > 0) continue;
                }
                Short cached = resolvedSpellActionIds.get(key);
                if (cached != null && cached > 0) continue;

                Short id = tryResolveSpellActionIdFromPlayer(s.lookupName);
                if (id != null && id > 0) {
                    resolvedSpellActionIds.put(key, id);
                    if (debug) Utils.consolePrint("DBG warmup act_id: %s (%s) -> %d", s.abbr, s.lookupName, (int) id);
                    resolvedCount++;
                }
            }

            if (debug) Utils.consolePrint("DBG warmup act_id done. Resolved=%d", resolvedCount);
        } catch (Throwable t) {
            if (debug) Utils.consolePrint("DBG warmup act_id failed: %s", String.valueOf(t.getMessage()));
        }
    }

    private void toggleOn() {
        enabled = !enabled;

        if (enabled) {
            // IMPORTANT: do not auto-enable casting anymore
            castingEnabled = false;
            skillerEnabled = false;

            castingCastInFlight = false;
            castingNextCastAllowedMs = 0L;
            castingCastSentAtMs = 0L;

            Utils.consolePrint("PriestBot enabled=true (casting/skiller are OFF by default). Use: bot priest cast OR bot priest skiller");
            Utils.consolePrint("Current spell: %s", activeSpell.abbr);

            if (activeSpell.enchantKey != null && enchants.containsKey(activeSpell.enchantKey)) {
                activeEnchantKey = activeSpell.enchantKey;
            }
            Utils.consolePrint("Active enchant: %s", activeEnchantKey);

            queueExaminesForSelectedItems();
        } else {
            Utils.consolePrint("PriestBot enabled=false (casting/skiller stopped)");

            castingEnabled = false;
            skillerEnabled = false;

            castingCastInFlight = false;
            castingNextCastAllowedMs = 0L;
            castingCastSentAtMs = 0L;

            skillerCastInFlight = false;
            skillerNextCastAllowedMs = 0L;
            skillerCastSentAtMs = 0L;
        }
    }
    private void toggleCasting() {
        castingEnabled = !castingEnabled;
        if (castingEnabled) {
            skillerEnabled = false;

            castingCastInFlight = false;
            castingNextCastAllowedMs = 0L;
            castingCastSentAtMs = 0L;

            if (!enabled) {
                enabled = true;
                Utils.consolePrint("PriestBot enabled=true (auto-enabled by cast)");
            }

            Utils.consolePrint("Casting enabled=true (spell=%s).", activeSpell.abbr);

            if (targetWindows.isEmpty() && manualGroundTargets.isEmpty()) {
                Utils.consolePrint("No target windows or manual ground targets. Use: bot priest addwin OR bot priest additem");
            }

            if (activeSpell.enchantKey != null && enchants.containsKey(activeSpell.enchantKey)) {
                activeEnchantKey = activeSpell.enchantKey;
            }
            Utils.consolePrint("Active enchant: %s", activeEnchantKey);

            // NEW: attempt to resolve dynamic act_ids without any menus (best-effort)
            warmUpDynamicSpellActionIdsFromPlayer();

            queueExaminesForSelectedItems();
        } else {
            Utils.consolePrint("Casting enabled=false");
            castingCastInFlight = false;
            castingNextCastAllowedMs = 0L;
            castingCastSentAtMs = 0L;
        }
    }
    private void toggleSkiller() {
        skillerEnabled = !skillerEnabled;
        if (skillerEnabled) castingEnabled = false;

        if (!skillerEnabled) {
            skillerCastInFlight = false;
            skillerNextCastAllowedMs = 0L;
            skillerCastSentAtMs = 0L;
        }

        if (skillerEnabled && !enabled) {
            enabled = true;
            Utils.consolePrint("PriestBot enabled=true (auto-enabled by skiller)");
        }

        if (skillerEnabled && (activeSpell == null || !activeSpell.isBodyCastAllowed)) {
            CastSpell fallback = CastSpell.LIGHT_TOKEN;

            try {
                World world = WurmHelper.hud.getWorld();
                PlayerObj player = world.getPlayer();
                float faith = player.getSkillSet().getSkillValue("faith");
                if (faith <= fallback.favorCap) {
                    Utils.consolePrint(
                            "Skiller default spell is %s, but you do not have enough faith (need faith>%d, you have %.2f).",
                            fallback.abbr, fallback.favorCap, faith
                    );
                    skillerEnabled = false;
                    skillerCastInFlight = false;
                    skillerNextCastAllowedMs = 0L;
                    skillerCastSentAtMs = 0L;
                    return;
                }
            } catch (Exception ignored) {
            }

            activeSpell = fallback;
            lastSelectedSpell = fallback;
            Utils.consolePrint("Skiller default spell selected: %s", activeSpell.abbr);
        }

        if (skillerEnabled && !activeSpell.isBodyCastAllowed) {
            Utils.consolePrint(
                    "Skiller only supports: ltoken|bless|dispel|wov (current spell=%s). Use: bot priest spell <abbr>",
                    activeSpell.abbr
            );
            skillerEnabled = false;
            skillerCastInFlight = false;
            skillerNextCastAllowedMs = 0L;
            skillerCastSentAtMs = 0L;
            return;
        }

        Utils.consolePrint("Skiller enabled=%s (spell=%s)", skillerEnabled, activeSpell.abbr);

        if (skillerEnabled) {
            skillerCastInFlight = false;
            skillerNextCastAllowedMs = 0L;
            skillerCastSentAtMs = 0L;

            if (!ensureStatuette()) Utils.consolePrint("Warning: can't find statuette yet.");
            if (!ensureBody()) Utils.consolePrint("Warning: can't find body target yet.");
        }
    }
    /**
     * Computes how many actions the player can queue.
     * Prefer the client-provided mind logic calculator via Utils.getMaxActionNumber().
     */
    private int getMaxQueuedActions() {
        try {
            int n = Utils.getMaxActionNumber();
            if (n > 0) return n;
        } catch (Exception ignored) {
        }
        return 3; // safe fallback
    }
    private void setSpell(String[] input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(InputKey.spell);
            return;
        }

        CastSpell s = CastSpell.getByAbbr(input[0]);
        if (s == null) {
            Utils.consolePrint(
                    "Unknown spell: %s (use: bot priest spell woa|coc|lt|botd|harden|venom|titanforged|efficiency|bagholding|expand|phasing|rottingtouch|bloodthirst|frostbrand|flamingaura|ltoken|bless|dispel|wov)",
                    input[0]
            );
            return;
        }

        // Faith gate: max possible favor is capped by faith
        try {
            World world = WurmHelper.hud.getWorld();
            PlayerObj player = world.getPlayer();
            float faith = player.getSkillSet().getSkillValue("faith");

            if (faith <= s.favorCap) {
                Utils.consolePrint(
                        "You do not have enough faith to select %s (need faith>%d, you have %.2f)",
                        s.abbr, s.favorCap, faith
                );
                return;
            }
        } catch (Exception ignored) {
        }

        activeSpell = s;
        lastSelectedSpell = s;

        // NEW: resolve server-specific act_id spells immediately (falls back safely if not found)
        refreshActiveSpellActionIdIfNeeded();

        Utils.consolePrint(
                "Spell set to %s (favor>=%d, base=%d, learnedOffset=+%d)",
                s.abbr,
                getFavorRequirement(s),
                s.favorCap,
                getLearnedFavorOffset(s)
        );

        if (s.enchantKey != null && enchants.containsKey(s.enchantKey)) {
            activeEnchantKey = s.enchantKey;
            Utils.consolePrint("Active compare enchant set to %s", activeEnchantKey);
        }

        // If user changes to a non-body spell while skiller is on, disable skiller.
        if (skillerEnabled && !s.isBodyCastAllowed) {
            skillerEnabled = false;
            skillerCastInFlight = false;
            skillerNextCastAllowedMs = 0L;
            skillerCastSentAtMs = 0L;
            Utils.consolePrint("Skiller disabled (spell %s is not a body spell).", s.abbr);
        }

        queueExaminesForSelectedItems();
    }

    private boolean ensureStatuette() {
        try {
            InventoryMetaItem statuette = Utils.locateToolItem("statuette of");
            if (statuette == null) return false;
            statuetteId = statuette.getId();
            return statuetteId > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ensureBody() {
        try {
            PaperDollInventory pdi = WurmHelper.hud.getPaperDollInventory();
            PaperDollSlot pds = Utils.getField(pdi, "bodyItem");
            if (pds == null) return false;
            bodyId = pds.getItemId();
            return bodyId > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private float getCurrentFavor() {
        try {
            World world = WurmHelper.hud.getWorld();
            PlayerObj player = world.getPlayer();
            return player.getSkillSet().getSkillValue("favor");
        } catch (Exception e) {
            return -1f; // unknown -> don't block casting
        }
    }
    /**
     * If the active spell uses a server-specific action id (act_id), resolve it from the player's spell list.
     * Safe to call often: cached and fast-fails.
     */
    private void refreshActiveSpellActionIdIfNeeded() {
        if (activeSpell == null) return;
        if (!activeSpell.usesDynamicActionId()) return;

        String key = activeSpell.lookupNameLower();
        if (key == null || key.isEmpty()) return;

        // 1) Best source: observed action ids (learned automatically from the UI/action system)
        Short observed;
        synchronized (observedActionLock) {
            observed = observedActionNameToId.get(key);
        }
        if (observed != null && observed > 0) {
            Short oldCached = resolvedSpellActionIds.get(key);
            short oldActive = activeSpell.getActionId();

            // Update caches / active spell
            resolvedSpellActionIds.put(key, observed);
            activeSpell.setActionId(observed);

            // Log only if this is new (first time) or changed
            boolean changed =
                    oldCached == null || oldCached <= 0 || oldCached.shortValue() != observed.shortValue()
                            || oldActive != observed.shortValue();

            if (debug && changed) {
                Utils.consolePrint("DBG act_id observed: %s -> %d", activeSpell.abbr, (int) observed);
            }
            return;
        }

        // 2) Next: our normal cache
        Short cached = resolvedSpellActionIds.get(key);
        if (cached != null && cached > 0) {
            activeSpell.setActionId(cached);
            return;
        }

        // 3) Fallback: try resolve from player's spell list (may fail on some clients)
        Short resolved = tryResolveSpellActionIdFromPlayer(activeSpell.lookupName);
        if (resolved != null && resolved > 0) {
            resolvedSpellActionIds.put(key, resolved);
            short oldActive = activeSpell.getActionId();
            activeSpell.setActionId(resolved);

            if (debug && oldActive != resolved.shortValue()) {
                Utils.consolePrint("DBG act_id resolved: %s -> %d", activeSpell.abbr, (int) resolved);
            }
        } else {
            if (debug) Utils.consolePrint(
                    "DBG act_id unresolved: %s (using fallback id=%d). Hint: open a right-click menu containing the spell once to auto-learn it.",
                    activeSpell.abbr,
                    (int) activeSpell.getActionId()
            );
        }
    }

    private Short tryResolveSpellActionIdFromPlayer(String spellName) {
        if (spellName == null || spellName.trim().isEmpty()) return null;

        final String wantedRaw = spellName.trim();
        final String wanted = wantedRaw.toLowerCase(Locale.US);
        final String wantedNorm = normalizeSpellName(wantedRaw);

        try {
            World world = WurmHelper.hud.getWorld();
            if (world == null) return null;

            PlayerObj player = world.getPlayer();
            if (player == null) return null;

            Object spellsObj = null;

            // Try likely spell-list getters on PlayerObj
            for (String mName : new String[]{"getSpells", "getSpellList", "getAvailableSpells", "getSpellbook"}) {
                try {
                    Method m = player.getClass().getMethod(mName);
                    spellsObj = m.invoke(player);
                    if (spellsObj != null) break;
                } catch (ReflectiveOperationException ignored) {
                }
            }

            // Fallback: try likely fields on PlayerObj
            if (spellsObj == null) {
                for (String fName : new String[]{"spells", "spellList", "availableSpells", "spellbook"}) {
                    try {
                        spellsObj = Utils.getField(player, fName);
                        if (spellsObj != null) break;
                    } catch (Exception ignored) {
                    }
                }
            }

            if (spellsObj == null) return null;

            Collection<?> spells = coerceToCollection(spellsObj);
            if (spells == null || spells.isEmpty()) return null;

            // If debug is on, keep a few names around to help diagnose mismatches
            ArrayList<String> dbgNames = debug ? new ArrayList<>() : null;

            for (Object sp : spells) {
                if (sp == null) continue;

                String nm = tryReadSpellName(sp);
                if (nm == null) continue;

                if (debug && dbgNames != null && dbgNames.size() < 20) {
                    dbgNames.add(nm);
                }

                String nmLower = nm.trim().toLowerCase(Locale.US);
                String nmNorm = normalizeSpellName(nm);

                boolean match =
                        nmLower.equals(wanted) ||
                                nmNorm.equals(wantedNorm) ||
                                // tolerate decorations/suffixes/prefixes
                                (wantedNorm.length() >= 4 && (nmNorm.contains(wantedNorm) || wantedNorm.contains(nmNorm)));

                if (!match) continue;

                Short id = tryReadSpellActionId(sp);
                if (id != null && id > 0) return id;
            }

            if (debug && dbgNames != null) {
                Utils.consolePrint(
                        "DBG act_id resolve: couldn't match spell name '%s'. Sample spell-list names: %s",
                        wantedRaw,
                        String.join(", ", dbgNames)
                );
            }
        } catch (Exception ignored) {
        }

        return null;
    }
    // Global cache: action name (lowercased) -> action id (observed from PlayerAction#getName)
    private static final Map<String, Short> observedActionNameToId = new HashMap<>();
    private static final Object observedActionLock = new Object();

    /**
     * Called from a low-level hook (PlayerAction#getName / PlayerAction constructors) to learn action ids automatically.
     */
    public static void observePlayerActionName(String name, short id) {
        if (name == null) return;
        if (id <= 0) return;

        String key = name.trim().toLowerCase(Locale.US);
        if (key.isEmpty()) return;

        boolean learned = false;

        synchronized (observedActionLock) {
            Short old = observedActionNameToId.get(key);
            if (old == null || old <= 0) {
                observedActionNameToId.put(key, id);
                learned = true;
            }
        }

        // Only log in PriestBot debug mode
        if (learned && observeActionDebug) {
            try {
                Utils.consolePrint("DBG learned action: %s -> %d", name, (int) id);
            } catch (Throwable ignored) {
                // never let logging break hooks
            }
        }
    }
    private static String normalizeSpellName(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US).trim();
        // keep letters/digits/spaces; drop punctuation/color-codes-ish noise
        StringBuilder out = new StringBuilder(t.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (ok) {
                out.append(c);
                lastWasSpace = false;
            } else if (Character.isWhitespace(c)) {
                if (!lastWasSpace) out.append(' ');
                lastWasSpace = true;
            } else {
                // skip
            }
        }
        return out.toString().trim();
    }
    private static Collection<?> coerceToCollection(Object v) {
        if (v == null) return null;

        if (v instanceof Collection) return (Collection<?>) v;

        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            ArrayList<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(java.lang.reflect.Array.get(v, i));
            return out;
        }

        for (String mName : new String[]{"values", "toList", "getSpells", "getSpellList"}) {
            try {
                Method m = v.getClass().getMethod(mName);
                Object nested = m.invoke(v);
                Collection<?> out = coerceToCollection(nested);
                if (out != null) return out;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return null;
    }

    private static String tryReadSpellName(Object sp) {
        for (String mName : new String[]{"getName", "getSpellName", "name"}) {
            try {
                Method m = sp.getClass().getMethod(mName);
                Object v = m.invoke(sp);
                if (v instanceof String) return (String) v;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        for (String fName : new String[]{"name", "spellName"}) {
            try {
                Object v = Utils.getField(sp, fName);
                if (v instanceof String) return (String) v;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Short tryReadSpellActionId(Object sp) {
        for (String mName : new String[]{"getActionId", "getAction", "getId", "getNumber"}) {
            try {
                Method m = sp.getClass().getMethod(mName);
                Object v = m.invoke(sp);
                Short s = coerceToShort(v);
                if (s != null && s > 0) return s;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        for (String fName : new String[]{"actionId", "action", "id", "number"}) {
            try {
                Object v = Utils.getField(sp, fName);
                Short s = coerceToShort(v);
                if (s != null && s > 0) return s;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Short coerceToShort(Object v) {
        if (v == null) return null;
        if (v instanceof Short) return (Short) v;
        if (v instanceof Number) return (short) ((Number) v).intValue();
        return null;
    }
    private InventoryMetaItem pickBestItemToCastOn(List<InventoryMetaItem> selected, EnchantCheck enchant) {
        if (selected == null || selected.isEmpty() || enchant == null) return null;

        List<InventoryMetaItem> eligible = new ArrayList<>();
        for (InventoryMetaItem it : selected) {
            if (it == null) continue;

            if (isTargetBroken(it.getId())) continue;
            boolean conflictsUnknown = false;
            String conflictKeyFound = null;
            double conflictPowerFound = 0.0;

            for (String conflictKey : activeSpell.conflictsEnchantKeys) {
                EnchantCheck conflictCheck = enchants.get(conflictKey);
                if (conflictCheck == null) continue;

                EnchantRead cr = readEnchant(it, conflictCheck);
                if (cr.state == EnchantState.UNKNOWN) {
                    conflictsUnknown = true;
                    break;
                }
                if (cr.state == EnchantState.PRESENT) {
                    conflictKeyFound = conflictKey;
                    conflictPowerFound = cr.power;
                    break;
                }
            }

            if (conflictsUnknown) return null;

            if (conflictKeyFound != null) {
                dbgSkippedConflictedEnchant(it.getId(), safeName(it), activeSpell, conflictKeyFound, conflictPowerFound);
                continue;
            }

            eligible.add(it);
        }
        if (eligible.isEmpty()) return null;

        for (InventoryMetaItem it : eligible) {
            EnchantRead r = readEnchant(it, enchant);
            if (r.state == EnchantState.UNKNOWN) return null;
        }

        eligible.sort((a, b) -> {
            EnchantRead ra = readEnchant(a, enchant);
            EnchantRead rb = readEnchant(b, enchant);
            int cmp = Double.compare(ra.power, rb.power);
            if (cmp != 0) return cmp;
            return Long.compare(a.getId(), b.getId());
        });

        return eligible.get(0);
    }

    private void dbgSkippedConflictedEnchant(long itemId, String itemName, CastSpell spell, String conflictEnchantKey, double conflictPower) {
        if (!debug) return;
        if (itemId <= 0 || spell == null || conflictEnchantKey == null) return;

        long now = System.currentTimeMillis();
        String throttleKey = spell.abbr + "|" + itemId + "|" + conflictEnchantKey;

        Long last = dbgConflictLastPrintMs.get(throttleKey);
        if (last != null && now - last < DBG_CONFLICT_THROTTLE_MS) return;
        dbgConflictLastPrintMs.put(throttleKey, now);

        String conflictName = conflictEnchantKey;
        EnchantCheck chk = enchants.get(conflictEnchantKey);
        if (chk != null && chk.displayName != null && !chk.displayName.isEmpty()) {
            conflictName = chk.displayName;
        }

        Utils.consolePrint(
                "DBG skip (conflict): spell=%s item=%s id=%d has=%s(%.2f)",
                spell.abbr,
                (itemName == null || itemName.trim().isEmpty()) ? "<item>" : itemName,
                itemId,
                conflictName,
                Math.max(0.0, conflictPower)
        );
    }

    private void toggleDebug() {
        debug = !debug;

        if (!debug) dbgConflictLastPrintMs.clear();
        observeActionDebug = debug;

        Utils.consolePrint("PriestBot debug=%s", debug);
    }

    private void addTargetWindow() {
        WurmComponent inventoryComponent = Utils.getTargetComponent(c -> c instanceof ItemListWindow || c instanceof InventoryWindow);
        if (inventoryComponent == null) {
            Utils.consolePrint("Didn't find an inventory window under cursor.");
            return;
        }
        try {
            InventoryListComponent ilc = Utils.getField(inventoryComponent, "component");
            if (ilc == null) {
                Utils.consolePrint("Unable to read window inventory component.");
                return;
            }
            targetWindows.add(ilc);
            Utils.consolePrint("Added target window. Total windows: %d", targetWindows.size());
        } catch (Exception e) {
            Utils.consolePrint("Unable to add target window.");
        }
    }

    private void clearTargetWindows() {
        targetWindows.clear();
        Utils.consolePrint("Cleared target windows.");
    }

    private void listConfiguredEnchants() {
        Utils.consolePrint("Configured enchants (use: bot priest use <key>):");
        for (EnchantCheck e : enchants.values()) {
            Utils.consolePrint(" - %s : %s (needles: %s)", e.key, e.displayName, String.join(", ", e.nameContainsLower));
        }
        Utils.consolePrint("Active enchant: %s", activeEnchantKey);

        Utils.consolePrint("Skiller body spells (use: bot priest spell <abbr> then: bot priest skiller):");
        for (CastSpell s : CastSpell.values()) {
            if (!s.isBodyCastAllowed) continue;
            Utils.consolePrint(" - %s (favor>=%d)", s.abbr, s.favorCap);
        }
    }

    private void setActiveEnchant(String[] input) {
        if (input == null || input.length < 1) {
            printInputKeyUsageString(InputKey.use);
            return;
        }

        String token = input[0] == null ? "" : input[0].trim().toLowerCase(Locale.US);
        if (token.isEmpty()) {
            printInputKeyUsageString(InputKey.use);
            return;
        }

        // If token matches a spell abbr -> treat as spell selection
        CastSpell asSpell = CastSpell.getByAbbr(token);
        if (asSpell != null) {
            setSpell(new String[]{token});
            return;
        }

        if (!enchants.containsKey(token)) {
            Utils.consolePrint("Unknown enchant/spell key: %s (use: bot priest list)", token);
            return;
        }

        activeEnchantKey = token;
        Utils.consolePrint("Active compare enchant set to: %s", activeEnchantKey);
        queueExaminesForSelectedItems();
    }

    private void scanOnceAndPrint() {
        if (skillerEnabled) return;

        boolean usingManual = !manualGroundTargets.isEmpty();

        if (!usingManual && targetWindows.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now - lastNoTargetWindowsPrintMs >= NO_TARGET_WINDOWS_THROTTLE_MS) {
                lastNoTargetWindowsPrintMs = now;
                Utils.consolePrint("No target windows. Use: bot priest addwin");
            }
            return;
        }

        if (activeEnchantKey == null || activeEnchantKey.isEmpty() || !enchants.containsKey(activeEnchantKey)) {
            activeEnchantKey = enchants.keySet().iterator().next();
        }

        EnchantCheck active = enchants.get(activeEnchantKey);

        // Manual ground targets scan
        if (usingManual) {
            queueExaminesForTargetIds(manualGroundTargets.keySet());

            List<Long> ids = new ArrayList<>(manualGroundTargets.keySet());

            int unknownCount = 0;
            int presentCount = 0;
            int missingCount = 0;

            double best = -1;
            double worstPresent = Double.MAX_VALUE;

            ids.sort((a, b) -> {
                EnchantRead ra = readEnchantById(a, active);
                EnchantRead rb = readEnchantById(b, active);

                int sa = ra.state.ordinal();
                int sb = rb.state.ordinal();
                if (sa != sb) return Integer.compare(sa, sb);

                int cmp = Double.compare(ra.power, rb.power);
                if (cmp != 0) return cmp;

                return Long.compare(a, b);
            });

            for (Long id : ids) {
                EnchantRead r = readEnchantById(id, active);
                switch (r.state) {
                    case UNKNOWN:
                        unknownCount++;
                        break;
                    case MISSING:
                        missingCount++;
                        break;
                    case PRESENT:
                        presentCount++;
                        best = Math.max(best, r.power);
                        worstPresent = Math.min(worstPresent, r.power);
                        break;
                }
            }

            Utils.consolePrint("---- Enchant compare: %s (%d targets) ----", active.displayName, ids.size());
            Utils.consolePrint("Present=%d Missing=%d Unknown=%d", presentCount, missingCount, unknownCount);
            if (presentCount > 0) {
                Utils.consolePrint("Best=%.2f  WorstPresent=%.2f", best, worstPresent);
            }

            int limit = Math.min(ids.size(), 30);
            for (int i = 0; i < limit; i++) {
                long id = ids.get(i);
                String name = manualGroundTargets.get(id);
                if (name == null || name.trim().isEmpty()) name = "<target>";

                EnchantRead r = readEnchantById(id, active);
                String pow = (r.state == EnchantState.UNKNOWN) ? "?" : String.format(Locale.US, "%.2f", r.power);

                Utils.consolePrint(
                        "%2d) %s  id=%d  %s=%s",
                        i + 1,
                        name,
                        id,
                        activeEnchantKey,
                        pow + " (" + r.state.name() + ")"
                );
            }
            if (ids.size() > limit) {
                Utils.consolePrint("... (%d more)", ids.size() - limit);
            }

            if (unknownCount > 0) {
                Utils.consolePrint("Some targets are UNKNOWN for %s -> waiting for EXAMINE learning (keep bot enabled).", activeEnchantKey);
            }
            return;
        }

        // Window items scan
        List<InventoryMetaItem> selected = getSelectedItemsFromWindows();
        if (selected.isEmpty()) {
            Utils.consolePrint("No selected items in target window(s).");
            return;
        }

        queueExaminesForSelectedItems(selected);

        List<Row> rows = new ArrayList<>();
        for (InventoryMetaItem it : selected) {
            if (it == null) continue;
            EnchantRead read = readEnchant(it, active);
            rows.add(new Row(it, read));
        }

        rows.sort((a, b) -> {
            int ua = a.read.state.ordinal();
            int ub = b.read.state.ordinal();
            if (ua != ub) return Integer.compare(ua, ub);
            int cmp = Double.compare(a.read.power, b.read.power);
            if (cmp != 0) return cmp;
            return Long.compare(a.item.getId(), b.item.getId());
        });

        int unknownCount = 0;
        int presentCount = 0;
        int missingCount = 0;

        double best = -1;
        double worstPresent = Double.MAX_VALUE;

        for (Row r : rows) {
            switch (r.read.state) {
                case UNKNOWN:
                    unknownCount++;
                    break;
                case MISSING:
                    missingCount++;
                    break;
                case PRESENT:
                    presentCount++;
                    best = Math.max(best, r.read.power);
                    worstPresent = Math.min(worstPresent, r.read.power);
                    break;
            }
        }

        Utils.consolePrint("---- Enchant compare: %s (%d items) ----", active.displayName, rows.size());
        Utils.consolePrint("Present=%d Missing=%d Unknown=%d", presentCount, missingCount, unknownCount);
        if (presentCount > 0) {
            Utils.consolePrint("Best=%.2f  WorstPresent=%.2f", best, worstPresent);
        }

        int limit = Math.min(rows.size(), 30);
        for (int i = 0; i < limit; i++) {
            Row r = rows.get(i);
            String name = safeName(r.item);
            String pow = (r.read.state == EnchantState.UNKNOWN) ? "?" : String.format(Locale.US, "%.2f", r.read.power);
            Utils.consolePrint("%2d) %s  id=%d  %s=%s", i + 1, name, r.item.getId(), activeEnchantKey, pow + " (" + r.read.state.name() + ")");
        }
        if (rows.size() > limit) {
            Utils.consolePrint("... (%d more)", rows.size() - limit);
        }

        if (unknownCount > 0) {
            Utils.consolePrint("Some items are UNKNOWN for %s -> waiting for EXAMINE learning (keep bot enabled).", activeEnchantKey);
        }
    }

    private EnchantRead readEnchant(InventoryMetaItem item, EnchantCheck enchant) {
        if (item == null || enchant == null) return EnchantRead.unknown();

        SpellEffect se = findFirstEffectByAnyNeedle(item, enchant.nameContainsLower);
        if (se != null) {
            double power = tryGetEffectPower(se);
            if (power < 0) power = 0;
            return EnchantRead.present(power);
        }

        Double cached = getCachedPower(item.getId(), enchant.key);
        if (cached != null) {
            if (cached <= 0.000001) return EnchantRead.missing();
            return EnchantRead.present(cached);
        }

        return EnchantRead.unknown();
    }

    private void addGroundItemUnderCursor() {
        try {
            World world = WurmHelper.hud.getWorld();
            int x = world.getClient().getXMouse();
            int y = world.getClient().getYMouse();

            long id = 0L;

            long[] targets = WurmHelper.hud.getCommandTargetsFrom(x, y);
            if (targets != null && targets.length > 0 && targets[0] > 0) {
                id = targets[0];
            } else {
                try {
                    Object hovered = world.getCurrentHoveredObject();
                    if (hovered != null) {
                        Method m = hovered.getClass().getMethod("getId");
                        Object v = m.invoke(hovered);
                        if (v instanceof Number) id = ((Number) v).longValue();
                    }
                } catch (Exception ignored) {
                }
            }

            if (id <= 0) {
                if (debug) {
                    Utils.consolePrint("DBG additem: no target (x=%d y=%d targets=%s hovered=%s)",
                            x, y,
                            (targets == null ? "null" : ("len=" + targets.length + " first=" + (targets.length > 0 ? targets[0] : 0))),
                            String.valueOf(world.getCurrentHoveredObject()));
                }
                Utils.consolePrint("No world object found under cursor. Hover the item on the ground and try again.");
                return;
            }

            String name = null;
            try {
                Object hovered = world.getCurrentHoveredObject();
                if (hovered != null) {
                    Method m = hovered.getClass().getMethod("getHoverName");
                    Object v = m.invoke(hovered);
                    if (v instanceof String) name = (String) v;
                }
            } catch (Exception ignored) {
            }

            if (name == null || name.trim().isEmpty()) name = "<target>";

            manualGroundTargets.put(id, name);
            Utils.consolePrint("Added ground target: %s (id=%d). Manual ground list size: %d", name, id, manualGroundTargets.size());

            queueExaminesForTargetIds(Collections.singleton(id));
        } catch (Exception e) {
            Utils.consolePrint("Unable to add ground item under cursor.");
        }
    }

    private void removeGroundItemUnderCursor() {
        try {
            World world = WurmHelper.hud.getWorld();
            int x = world.getClient().getXMouse();
            int y = world.getClient().getYMouse();

            long id = 0L;

            long[] targets = WurmHelper.hud.getCommandTargetsFrom(x, y);
            if (targets != null && targets.length > 0 && targets[0] > 0) {
                id = targets[0];
            } else {
                try {
                    Object hovered = world.getCurrentHoveredObject();
                    if (hovered != null) {
                        Method m = hovered.getClass().getMethod("getId");
                        Object v = m.invoke(hovered);
                        if (v instanceof Number) id = ((Number) v).longValue();
                    }
                } catch (Exception ignored) {
                }
            }

            if (id <= 0) {
                if (debug) {
                    Utils.consolePrint("DBG itemremove: no target (x=%d y=%d targets=%s hovered=%s)",
                            x, y,
                            (targets == null ? "null" : ("len=" + targets.length + " first=" + (targets.length > 0 ? targets[0] : 0))),
                            String.valueOf(world.getCurrentHoveredObject()));
                }
                Utils.consolePrint("No world object found under cursor. Hover the item on the ground and try again.");
                return;
            }

            String removedName = manualGroundTargets.remove(id);
            if (removedName == null) {
                Utils.consolePrint("That target (id=%d) is not in the manual ground list. Size: %d", id, manualGroundTargets.size());
                return;
            }

            examinedPowerCache.remove(id);
            lastExamineRequestMs.remove(id);
            final long targetId = id;
            examineQueue.removeIf(q -> q == targetId);

            Utils.consolePrint("Removed ground target: %s (id=%d). Manual ground list size: %d", removedName, id, manualGroundTargets.size());
        } catch (Exception e) {
            Utils.consolePrint("Unable to remove ground item under cursor.");
        }
    }

    private void queueExaminesForTargetIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        if (enchants.isEmpty()) return;

        for (Long boxed : ids) {
            if (boxed == null) continue;
            long id = boxed;
            if (id <= 0) continue;

            Map<String, Double> cachedMap = examinedPowerCache.get(id);
            boolean missingAny = false;

            if (cachedMap == null) {
                missingAny = true;
            } else {
                for (String key : enchants.keySet()) {
                    if (!cachedMap.containsKey(key)) {
                        missingAny = true;
                        break;
                    }
                }
            }

            if (missingAny && !examineQueue.contains(id)) {
                examineQueue.addLast(id);
            }
        }
    }

    private EnchantRead readEnchantById(long targetId, EnchantCheck enchant) {
        if (targetId <= 0 || enchant == null) return EnchantRead.unknown();

        Double cached = getCachedPower(targetId, enchant.key);
        if (cached != null) {
            if (cached <= 0.000001) return EnchantRead.missing();
            return EnchantRead.present(cached);
        }
        return EnchantRead.unknown();
    }

    private Long pickBestTargetIdToCastOn(Collection<Long> targetIds, EnchantCheck enchant) {
        if (targetIds == null || targetIds.isEmpty() || enchant == null) return null;

        List<Long> eligible = new ArrayList<>();
        for (Long boxed : targetIds) {
            if (boxed == null || boxed <= 0) continue;
            long id = boxed;

            if (isTargetBroken(id)) continue;
            boolean conflictsUnknown = false;
            String conflictKeyFound = null;
            double conflictPowerFound = 0.0;

            for (String conflictKey : activeSpell.conflictsEnchantKeys) {
                EnchantCheck conflictCheck = enchants.get(conflictKey);
                if (conflictCheck == null) continue;

                EnchantRead cr = readEnchantById(id, conflictCheck);
                if (cr.state == EnchantState.UNKNOWN) {
                    conflictsUnknown = true;
                    break;
                }
                if (cr.state == EnchantState.PRESENT) {
                    conflictKeyFound = conflictKey;
                    conflictPowerFound = cr.power;
                    break;
                }
            }

            if (conflictsUnknown) return null;
            if (conflictKeyFound != null) {
                String nm = manualGroundTargets.get(id);
                dbgSkippedConflictedEnchant(id, (nm == null ? "<target>" : nm), activeSpell, conflictKeyFound, conflictPowerFound);
                continue;
            }

            eligible.add(id);
        }

        if (eligible.isEmpty()) return null;

        for (Long id : eligible) {
            EnchantRead r = readEnchantById(id, enchant);
            if (r.state == EnchantState.UNKNOWN) return null;
        }

        eligible.sort((a, b) -> {
            EnchantRead ra = readEnchantById(a, enchant);
            EnchantRead rb = readEnchantById(b, enchant);
            int cmp = Double.compare(ra.power, rb.power);
            if (cmp != 0) return cmp;
            return Long.compare(a, b);
        });

        return eligible.get(0);
    }

    private Double getCachedPower(long itemId, String enchantKey) {
        Map<String, Double> m = examinedPowerCache.get(itemId);
        return (m == null) ? null : m.get(enchantKey);
    }

    private void queueExaminesForSelectedItems() {
        queueExaminesForSelectedItems(getSelectedItemsFromWindows());
    }

    private Float tryGetActionProgress() {
        try {
            CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
            if (creationWindow == null) return null;

            Object progressBar = Utils.getField(creationWindow, "progressBar");
            if (progressBar == null) return null;

            Object p = Utils.getField(progressBar, "progress");
            if (p instanceof Float) return (Float) p;
            if (p instanceof Number) return ((Number) p).floatValue();

            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void queueExaminesForSelectedItems(List<InventoryMetaItem> items) {
        if (items == null || items.isEmpty()) return;
        if (enchants.isEmpty()) return;

        for (InventoryMetaItem it : items) {
            if (it == null) continue;

            boolean hasSpellEffects = false;
            try {
                List<SpellEffect> effects = getSpellEffectsFromItem(it);
                hasSpellEffects = effects != null && !effects.isEmpty();
            } catch (Exception ignored) {
            }
            if (hasSpellEffects) continue;

            long id = it.getId();
            Map<String, Double> cachedMap = examinedPowerCache.get(id);

            boolean missingAny = false;
            if (cachedMap == null) {
                missingAny = true;
            } else {
                for (String key : enchants.keySet()) {
                    if (!cachedMap.containsKey(key)) {
                        missingAny = true;
                        break;
                    }
                }
            }

            if (missingAny && !examineQueue.contains(id)) {
                examineQueue.addLast(id);
            }
        }
    }

    private boolean pumpExamineQueue() {
        long now = System.currentTimeMillis();

        if (pendingExamineItemId > 0 && now <= pendingExamineUntilMs) {
            return true;
        }

        while (!examineQueue.isEmpty()) {
            long id = examineQueue.peekFirst();

            long last = lastExamineRequestMs.getOrDefault(id, 0L);
            if (now - last < EXAMINE_THROTTLE_MS) return true;

            examineQueue.removeFirst();
            lastExamineRequestMs.put(id, now);

            pendingExamineItemId = id;
            pendingExamineUntilMs = now + EXAMINE_WINDOW_MS;
            pendingExamineSawAnyLine = false;
            pendingFoundEnchantKeys.clear();

            try {
                WurmHelper.hud.sendAction(PlayerAction.EXAMINE, id);
                if (debug) Utils.consolePrint("DBG examine: sent EXAMINE for id=%d", id);
            } catch (Exception e) {
                if (debug) Utils.consolePrint("DBG examine: failed EXAMINE for id=%d", id);
                pendingExamineItemId = 0;
                pendingExamineUntilMs = 0;
                pendingExamineSawAnyLine = false;
                pendingFoundEnchantKeys.clear();
            }
            return true;
        }

        return false;
    }

    private void registerEventProcessors() {
        // Casting events (for waiting)
        registerEventProcessor(
                msg -> msg != null && (msg.contains("You start to cast") || msg.contains("you will start casting")),
                () -> successfulCastStart = true
        );

        // Favor calibration: increase requirement when server says we need more favor with god
        registerEventProcessor(
                msg -> {
                    if (msg == null) return false;
                    String m = msg.toLowerCase(Locale.US);
                    return m.contains("need more favor with your god") && m.contains("cast that spell");
                },
                () -> bumpFavorRequirementUp(activeSpell, "server said need more favor")
        );

        // SUCCESS: only this should be allowed to down-adjust
        registerEventProcessor(
                msg -> msg != null && msg.contains("You cast "),
                () -> {
                    try {
                        maybeBumpFavorRequirementDownAfterSuccess(activeSpell);
                    } catch (Throwable ignored) {
                    }

                    successfulCasting = true;

                    skillerCastInFlight = false;
                    skillerCastSentAtMs = 0L;
                    skillerConsecutiveTimeouts = 0; // NEW

                    castingCastInFlight = false;
                    castingCastSentAtMs = 0L;
                    castingNextCastAllowedMs = Math.max(castingNextCastAllowedMs, System.currentTimeMillis() + 350L);
                }
        );

        // FAIL/BLOCK: do NOT down-adjust on these
        registerEventProcessor(
                msg -> {
                    if (msg == null) return false;
                    if (msg.contains("You fail to channel")) return true;
                    if (msg.contains("You must not move")) return true;
                    if (msg.contains("You frown as you fail to improve the power.")) return true;
                    return false;
                },
                () -> {
                    successfulCasting = true;

                    skillerCastInFlight = false;
                    skillerCastSentAtMs = 0L;
                    skillerConsecutiveTimeouts = 0; // NEW

                    castingCastInFlight = false;
                    castingCastSentAtMs = 0L;
                    castingNextCastAllowedMs = Math.max(castingNextCastAllowedMs, System.currentTimeMillis() + 350L);
                }
        );

        // NEW: common "end of action" / "blocked" messages that should also clear in-flight
        registerEventProcessor(
                msg -> {
                    if (msg == null) return false;
                    if (msg.contains("You are too busy")) return true;
                    if (msg.contains("You stop casting")) return true;
                    if (msg.contains("You can't cast")) return true;
                    if (msg.contains("Nothing happens")) return true;
                    if (msg.contains("You do not have enough favor")) return true;
                    return false;
                },
                () -> {
                    skillerCastInFlight = false;
                    skillerCastSentAtMs = 0L;
                    // don't reset consecutive timeouts here; this is a real signal we got *something*
                    skillerNextCastAllowedMs = Math.max(skillerNextCastAllowedMs, System.currentTimeMillis() + 1_000L);
                }
        );

        // Item destroyed: remove it from enchanting (session)
        registerEventProcessor(
                msg -> {
                    if (msg == null) return false;
                    // Example: "The large anvil emits a strong deep sound of resonance, then shatters!"
                    return msg.contains(" then shatters!");
                },
                () -> markTargetBroken(lastCastTargetId, "shattered")
        );

        // Cooldown message example:
        // "You need to wait 2 minutes until you can cast Bag of Holding again."
        registerEventProcessor(
                msg -> msg != null && msg.contains("You need to wait") && msg.contains("until you can cast") && msg.contains("again."),
                () -> {
                    // no-op: parsed below
                }
        );
        registerEventProcessor(
                msg -> {
                    if (msg == null) return false;
                    if (!msg.contains("You need to wait") || !msg.contains("until you can cast") || !msg.contains("again."))
                        return false;

                    Long waitMs = tryParseWaitMs(msg);
                    if (waitMs == null || waitMs <= 0) return false;

                    CastSpell s = tryDetectSpellFromCooldownMessage(msg);
                    if (s == null) s = activeSpell;

                    long until = System.currentTimeMillis() + waitMs;
                    long old = spellCooldownUntilMs.getOrDefault(s, 0L);
                    if (until > old) spellCooldownUntilMs.put(s, until);

                    if (debug) {
                        Utils.consolePrint("DBG cooldown: spell=%s waitMs=%d", s.abbr, waitMs);
                    }
                    return false;
                },
                () -> {
                    // no-op
                }
        );

        // Pray busy handling: if we overfill the queue anyway, delay the next chunk slightly.
        // Example message: "[21:58:11] You're too busy."
        registerEventProcessor(
                msg -> msg != null && msg.contains("You're too busy"),
                () -> {
                    if (!praying) return;
                    long now = System.currentTimeMillis();
                    prayNextChunkAtMs = Math.max(prayNextChunkAtMs, now + PRAY_BUSY_BACKOFF_MS);
                    if (debug) Utils.consolePrint("DBG pray: too busy -> backing off %d ms", PRAY_BUSY_BACKOFF_MS);
                }
        );

        // EXAMINE parsing
        registerEventProcessor(
                msg -> {
                    if (pendingExamineItemId <= 0) return false;
                    if (System.currentTimeMillis() > pendingExamineUntilMs) return false;
                    if (msg == null) return false;

                    pendingExamineSawAnyLine = true;

                    String ml = msg.toLowerCase(Locale.US);

                    for (EnchantCheck e : enchants.values()) {
                        int idx = indexOfAnyNeedle(ml, e.nameContainsLower);
                        if (idx < 0) continue;

                        String needle = matchedNeedle(ml, e.nameContainsLower);
                        int startIdx = (needle == null) ? idx : (idx + needle.length());

                        Double power = tryParseNumberNear(ml, startIdx);

                        // Some servers report Bag of Holding as "Courier has been cast on it..."
                        if (power == null
                                && BuiltInEnchant.BAG_OF_HOLDING.key.equals(e.key)
                                && ml.contains(" has been cast on it")) {
                            power = 1.0;
                        }

                        if (power == null) continue;

                        examinedPowerCache
                                .computeIfAbsent(pendingExamineItemId, _k -> new HashMap<>())
                                .put(e.key, power);

                        pendingFoundEnchantKeys.add(e.key);

                        if (debug) {
                            Utils.consolePrint("DBG examine: cached %s=%.2f for itemId=%d",
                                    e.key, power, pendingExamineItemId);
                        }
                    }

                    return false;
                },
                () -> {
                    // no-op
                }
        );
    }

    private CastSpell tryDetectSpellFromCooldownMessage(String msg) {
        if (msg == null) return null;

        int a = msg.indexOf("until you can cast ");
        if (a < 0) return null;
        a += "until you can cast ".length();

        int b = msg.lastIndexOf(" again.");
        if (b < 0 || b <= a) return null;

        String spellName = msg.substring(a, b).trim();
        if (spellName.isEmpty()) return null;

        for (CastSpell s : CastSpell.values()) {
            if (s.abbr.equalsIgnoreCase(spellName)) return s;
            if (s == CastSpell.BAG_OF_HOLDING && spellName.equalsIgnoreCase("Bag of Holding")) return s;
        }
        return null;
    }

    private Long tryParseWaitMs(String msg) {
        if (msg == null) return null;

        long totalSec = 0;

        totalSec += parseUnitSeconds(msg, "minute", 60);
        totalSec += parseUnitSeconds(msg, "minutes", 60);
        totalSec += parseUnitSeconds(msg, "second", 1);
        totalSec += parseUnitSeconds(msg, "seconds", 1);

        return totalSec > 0 ? totalSec * 1000L : null;
    }

    private long parseUnitSeconds(String msg, String unitWord, int unitSeconds) {
        int idx = msg.indexOf(unitWord);
        if (idx < 0) return 0;

        int i = idx - 1;
        while (i >= 0 && !Character.isDigit(msg.charAt(i))) i--;
        if (i < 0) return 0;

        int end = i + 1;
        int start = i;
        while (start >= 0 && Character.isDigit(msg.charAt(start))) start--;
        start++;

        try {
            long n = Long.parseLong(msg.substring(start, end));
            return Math.max(0, n) * unitSeconds;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void finalizeExpiredPendingExamineIfNeeded() {
        if (pendingExamineItemId <= 0) return;
        if (System.currentTimeMillis() <= pendingExamineUntilMs) return;

        if (pendingExamineSawAnyLine) {
            Map<String, Double> map = examinedPowerCache.computeIfAbsent(pendingExamineItemId, _k -> new HashMap<>());

            for (EnchantCheck e : enchants.values()) {
                if (!map.containsKey(e.key)) {
                    map.put(e.key, 0.0);
                    if (debug) {
                        Utils.consolePrint("DBG examine: cached %s=0.0 (not found) for itemId=%d", e.key, pendingExamineItemId);
                    }
                }
            }
        }

        pendingExamineItemId = 0;
        pendingExamineUntilMs = 0;
        pendingExamineSawAnyLine = false;
        pendingFoundEnchantKeys.clear();
    }

    private List<InventoryMetaItem> getSelectedItemsFromWindows() {
        List<InventoryMetaItem> selected = new ArrayList<>();
        for (InventoryListComponent ilc : targetWindows) {
            try {
                List<InventoryMetaItem> inWindow = Utils.getSelectedItems(ilc, false, true);
                if (inWindow != null) selected.addAll(inWindow);
            } catch (Exception ignored) {
            }
        }
        if (selected.size() <= 1) return selected;

        LinkedHashMap<Long, InventoryMetaItem> uniq = new LinkedHashMap<>();
        for (InventoryMetaItem it : selected) {
            if (it == null) continue;
            uniq.put(it.getId(), it);
        }
        return new ArrayList<>(uniq.values());
    }

    private SpellEffect findFirstEffectByAnyNeedle(InventoryMetaItem item, List<String> needlesLower) {
        if (item == null || needlesLower == null || needlesLower.isEmpty()) return null;

        List<SpellEffect> effects = getSpellEffectsFromItem(item);
        if (effects == null || effects.isEmpty()) return null;

        for (SpellEffect e : effects) {
            String name;
            try {
                name = e.getName();
            } catch (Exception ignored) {
                continue;
            }
            if (name == null) continue;
            String nl = name.toLowerCase(Locale.US);
            for (String needle : needlesLower) {
                if (needle != null && !needle.isEmpty() && nl.contains(needle)) return e;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<SpellEffect> getSpellEffectsFromItem(InventoryMetaItem item) {
        if (item == null) return Collections.emptyList();

        for (String methodName : new String[]{"getSpellEffects", "getEffects", "getSpellEffectSet"}) {
            try {
                Method m = item.getClass().getMethod(methodName);
                Object result = m.invoke(item);
                List<SpellEffect> effects = coerceToSpellEffectList(result);
                if (effects != null) return effects;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (String fieldName : new String[]{"spellEffects", "effects", "spellEffectSet"}) {
            try {
                Object v = Utils.getField(item, fieldName);
                List<SpellEffect> effects = coerceToSpellEffectList(v);
                if (effects != null) return effects;
            } catch (Exception ignored) {
            }
        }

        try {
            for (Field f : item.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(item);
                List<SpellEffect> effects = coerceToSpellEffectList(v);
                if (effects != null) return effects;
            }
        } catch (Exception ignored) {
        }

        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<SpellEffect> coerceToSpellEffectList(Object v) {
        if (v == null) return null;

        if (v instanceof List) {
            List<?> list = (List<?>) v;
            if (list.isEmpty()) return Collections.emptyList();
            if (list.get(0) instanceof SpellEffect) return (List<SpellEffect>) list;
        }

        if (v instanceof Collection) {
            List<SpellEffect> out = new ArrayList<>();
            for (Object o : (Collection<?>) v) {
                if (o instanceof SpellEffect) out.add((SpellEffect) o);
            }
            return out;
        }

        for (String methodName : new String[]{"getEffects", "getSpellEffects", "values", "toList"}) {
            try {
                Method m = v.getClass().getMethod(methodName);
                Object nested = m.invoke(v);
                List<SpellEffect> out = coerceToSpellEffectList(nested);
                if (out != null) return out;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return null;
    }

    private double tryGetEffectPower(SpellEffect e) {
        if (e == null) return 0.0;

        for (String methodName : new String[]{"getPower", "getEffectPower", "getValue", "getStrength"}) {
            try {
                Method m = e.getClass().getMethod(methodName);
                Object v = m.invoke(e);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (String fieldName : new String[]{"power", "effectPower", "value", "strength"}) {
            try {
                Object v = Utils.getField(e, fieldName);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Exception ignored) {
            }
        }

        return 0.0;
    }

    private static Double tryParseNumberNear(String sLower, int startIdx) {
        if (sLower == null) return null;
        int i = Math.max(0, Math.min(startIdx, sLower.length()));

        while (i < sLower.length()) {
            char c = sLower.charAt(i);
            if (Character.isDigit(c) || c == '-') break;
            i++;
        }
        if (i >= sLower.length()) return null;

        int j = i;
        boolean dotSeen = false;
        while (j < sLower.length()) {
            char c = sLower.charAt(j);
            if (Character.isDigit(c)) {
                j++;
                continue;
            }
            if (c == '.' && !dotSeen) {
                dotSeen = true;
                j++;
                continue;
            }
            break;
        }
        if (j <= i) return null;

        try {
            return Double.parseDouble(sLower.substring(i, j));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int indexOfAnyNeedle(String hayLower, List<String> needlesLower) {
        if (hayLower == null || needlesLower == null) return -1;
        int best = -1;
        for (String n : needlesLower) {
            if (n == null || n.isEmpty()) continue;
            int idx = hayLower.indexOf(n);
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        return best;
    }

    private static String matchedNeedle(String hayLower, List<String> needlesLower) {
        if (hayLower == null || needlesLower == null) return null;
        int bestIdx = Integer.MAX_VALUE;
        String bestNeedle = null;
        for (String n : needlesLower) {
            if (n == null || n.isEmpty()) continue;
            int idx = hayLower.indexOf(n);
            if (idx >= 0 && idx < bestIdx) {
                bestIdx = idx;
                bestNeedle = n;
            }
        }
        return bestNeedle;
    }

    private static String safeName(InventoryMetaItem it) {
        if (it == null) return "<null>";
        try {
            String n = it.getDisplayName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) {
        }
        try {
            String n = it.getBaseName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) {
        }
        return "<item>";
    }

    private void addBuiltIn(BuiltInEnchant b) {
        enchants.put(b.key, new EnchantCheck(b.key, b.displayName, Arrays.asList(b.needlesLower)));
    }

    private enum BuiltInEnchant {
        WOA("woa", "Wind of Ages", new String[]{"wind of ages"}),
        COC("coc", "Circle of Cunning", new String[]{"circle of cunning"}),
        BOTD("botd", "Blessings of the Dark", new String[]{"blessings of the dark"}),
        LT("lt", "Life Transfer", new String[]{"life transfer"}),
        VEN("ven", "Venom", new String[]{"venom"}),

        HARDEN("harden", "Harden", new String[]{"harden"}),
        TITANFORGED("titanforged", "Titanforged", new String[]{"titanforged"}),
        EFFICIENCY("efficiency", "Efficiency", new String[]{"efficiency"}),
        BAG_OF_HOLDING("bagholding", "Bag of Holding", new String[]{"bag of holding", "bag holding", "courier"}),
        EXPAND("expand", "Expand", new String[]{"expand"}),
        PHASING("phasing", "Phasing", new String[]{"phasing"}),

        ROTTING_TOUCH("rottingtouch", "Rotting Touch", new String[]{"rotting touch"}),
        BLOODTHIRST("bloodthirst", "Bloodthirst", new String[]{"bloodthirst"}),
        FROSTBRAND("frostbrand", "Frostbrand", new String[]{"frostbrand"}),
        FLAMING_AURA("flamingaura", "Flaming Aura", new String[]{"flaming aura"}),

        AURA_OF_SHARED_PAIN("auraofsharedpain", "Aura of Shared Pain", new String[]{"aura of shared pain"}),
        WEB_ARMOUR("webarmour", "Web Armour", new String[]{"web armour", "web armour"}),
        DIRT("dirt", "Dirt", new String[]{"dirt"}),
        MIND_STEALER("mindstealer", "Mind Stealer", new String[]{"mind stealer"}),
        NOLOCATE("nolocate", "Nolocate", new String[]{"nolocate", "no locate"}),
        VESSEL("vessel", "Vessel", new String[]{"vessel"}),
        NIMBLENESS("nimbleness", "Nimbleness", new String[]{"nimbleness"});

        final String key;
        final String displayName;
        final String[] needlesLower;

        BuiltInEnchant(String key, String displayName, String[] needlesLower) {
            this.key = key;
            this.displayName = displayName;
            this.needlesLower = needlesLower;
        }
    }

    private static final class EnchantCheck {
        final String key;
        final String displayName;
        final List<String> nameContainsLower;

        EnchantCheck(String key, String displayName, List<String> nameContainsLower) {
            this.key = key;
            this.displayName = displayName;
            this.nameContainsLower = (nameContainsLower == null) ? Collections.<String>emptyList() : nameContainsLower;
        }
    }

    private enum EnchantState {UNKNOWN, MISSING, PRESENT}

    private static final class EnchantRead {
        final EnchantState state;
        final double power;

        private EnchantRead(EnchantState state, double power) {
            this.state = state;
            this.power = power;
        }

        static EnchantRead unknown() {
            return new EnchantRead(EnchantState.UNKNOWN, 0.0);
        }

        static EnchantRead missing() {
            return new EnchantRead(EnchantState.MISSING, 0.0);
        }

        static EnchantRead present(double power) {
            return new EnchantRead(EnchantState.PRESENT, Math.max(0.0, power));
        }
    }

    private static final class Row {
        final InventoryMetaItem item;
        final EnchantRead read;

        Row(InventoryMetaItem item, EnchantRead read) {
            this.item = item;
            this.read = read;
        }
    }

    private enum CastSpell {
        WOA("woa", BuiltInEnchant.WOA.key, 50, PlayerAction.WIND_OF_AGES, null, 0,
                new HashSet<>(Arrays.asList(BuiltInEnchant.BOTD.key, BuiltInEnchant.NIMBLENESS.key)), false),
        COC("coc", BuiltInEnchant.COC.key, 50, PlayerAction.CIRCLE_OF_CUNNING, null, 0,
                new HashSet<>(Arrays.asList(BuiltInEnchant.BOTD.key)), false),
        NIMBLENESS("nimbleness", BuiltInEnchant.NIMBLENESS.key, 60, PlayerAction.NIMBLENESS, null, 0,
                new HashSet<>(Arrays.asList(BuiltInEnchant.BOTD.key, BuiltInEnchant.WOA.key)), false),
        LT("lt", BuiltInEnchant.LT.key, 50, PlayerAction.LIFETRANSFER, null, 0,
                new HashSet<>(Arrays.asList(
                        BuiltInEnchant.FLAMING_AURA.key,
                        BuiltInEnchant.FROSTBRAND.key,
                        BuiltInEnchant.ROTTING_TOUCH.key,
                        BuiltInEnchant.VEN.key,
                        BuiltInEnchant.BLOODTHIRST.key
                )), false),

        BOTD("botd", BuiltInEnchant.BOTD.key, 70, PlayerAction.BLESSINGS_OF_THE_DARK, null, 0,
                new HashSet<>(Arrays.asList(BuiltInEnchant.COC.key, BuiltInEnchant.WOA.key, BuiltInEnchant.NIMBLENESS.key)), false),

        VEN("venom", BuiltInEnchant.VEN.key, 90, PlayerAction.VENOM, null, 0,
                new HashSet<>(Arrays.asList(
                        BuiltInEnchant.BLOODTHIRST.key,
                        BuiltInEnchant.LT.key,
                        BuiltInEnchant.ROTTING_TOUCH.key,
                        BuiltInEnchant.FROSTBRAND.key,
                        BuiltInEnchant.FLAMING_AURA.key
                )), false),

        ROTTING_TOUCH("rottingtouch", BuiltInEnchant.ROTTING_TOUCH.key, 40, PlayerAction.ROTTING_TOUCH, null, 0,
                new HashSet<>(Arrays.asList(
                        BuiltInEnchant.VEN.key,
                        BuiltInEnchant.FROSTBRAND.key,
                        BuiltInEnchant.FLAMING_AURA.key,
                        BuiltInEnchant.BLOODTHIRST.key,
                        BuiltInEnchant.LT.key
                )), false),

        BLOODTHIRST("bloodthirst", BuiltInEnchant.BLOODTHIRST.key, 50, PlayerAction.BLOODTHIRST, null, 0,
                new HashSet<>(Arrays.asList(
                        BuiltInEnchant.FLAMING_AURA.key,
                        BuiltInEnchant.FROSTBRAND.key,
                        BuiltInEnchant.LT.key,
                        BuiltInEnchant.ROTTING_TOUCH.key,
                        BuiltInEnchant.VEN.key
                )), false),

        FROSTBRAND("frostbrand", BuiltInEnchant.FROSTBRAND.key, 45, PlayerAction.FROSTBRAND, null, 0,
                new HashSet<>(Arrays.asList(
                        BuiltInEnchant.BLOODTHIRST.key,
                        BuiltInEnchant.LT.key,
                        BuiltInEnchant.ROTTING_TOUCH.key,
                        BuiltInEnchant.VEN.key,
                        BuiltInEnchant.FLAMING_AURA.key
                )), false),

        FLAMING_AURA("flamingaura", BuiltInEnchant.FLAMING_AURA.key, 45, PlayerAction.FLAMING_AURA, null, 0,
                new HashSet<>(Arrays.asList(
                        BuiltInEnchant.BLOODTHIRST.key,
                        BuiltInEnchant.LT.key,
                        BuiltInEnchant.ROTTING_TOUCH.key,
                        BuiltInEnchant.VEN.key,
                        BuiltInEnchant.FROSTBRAND.key
                )), false),

        AURA_OF_SHARED_PAIN("auraofsharedpain", BuiltInEnchant.AURA_OF_SHARED_PAIN.key, 35, PlayerAction.AURA_OF_SHARED_PAIN, null, 0,
                new HashSet<>(Arrays.asList(BuiltInEnchant.WEB_ARMOUR.key)), false),

        WEB_ARMOUR("webarmour", BuiltInEnchant.WEB_ARMOUR.key, 35, PlayerAction.WEB_ARMOUR, null, 0,
                new HashSet<>(Arrays.asList(BuiltInEnchant.AURA_OF_SHARED_PAIN.key)), false),

        DIRT("dirt", BuiltInEnchant.DIRT.key, 4, PlayerAction.DIRT_SPELL, null, 0,
                new HashSet<>(Arrays.asList()), false),

        MIND_STEALER("mindstealer", BuiltInEnchant.MIND_STEALER.key, 90, PlayerAction.MIND_STEALER, null, 0,
                new HashSet<>(Arrays.asList()), false),

        NOLOCATE("nolocate", BuiltInEnchant.NOLOCATE.key, 60, PlayerAction.NOLOCATE, null, 0,
                new HashSet<>(Arrays.asList()), false),

        VESSEL("vessel", BuiltInEnchant.VESSEL.key, 5, PlayerAction.VESSEL, null, 0,
                new HashSet<>(Arrays.asList()), false),

        // Server-specific act_id spells (fallback ids kept, but resolved per server using lookupName)
        HARDEN("harden", BuiltInEnchant.HARDEN.key, 10, null, "Harden", (short) 1458,
                new HashSet<>(Arrays.asList()), false),
        TITANFORGED("titanforged", BuiltInEnchant.TITANFORGED.key, 90, null, "Titanforged", (short) 1468,
                new HashSet<>(Arrays.asList()), false),
        EFFICIENCY("efficiency", BuiltInEnchant.EFFICIENCY.key, 90, null, "Efficiency", (short) 1462,
                new HashSet<>(Arrays.asList()), false),
        BAG_OF_HOLDING("bagholding", BuiltInEnchant.BAG_OF_HOLDING.key, 30, null, "Bag of Holding", (short) 1478,
                new HashSet<>(Arrays.asList()), false),
        EXPAND("expand", BuiltInEnchant.EXPAND.key, 40, null, "Expand", (short) 1461,
                new HashSet<>(Arrays.asList()), false),
        PHASING("phasing", BuiltInEnchant.PHASING.key, 30, null, "Phasing", (short) 1459,
                new HashSet<>(Arrays.asList()), false),

        // Body-only skiller spells (no compare enchant key)
        LIGHT_TOKEN("ltoken", null, 5, PlayerAction.LIGHT_TOKEN, null, 0, new HashSet<>(Arrays.asList()), true),
        BLESS("bless", null, 10, PlayerAction.BLESS, null, 0, new HashSet<>(Arrays.asList()), true),
        DISPEL("dispel", null, 10, PlayerAction.DISPEL, null, 0, new HashSet<>(Arrays.asList()), true),
        WOV("wov", null, 30, PlayerAction.WISDOM_OF_VYNORA, null, 0, new HashSet<>(Arrays.asList()), true);

        final String abbr;
        final String enchantKey;
        final int favorCap;

        // Built-in spells use this
        final PlayerAction builtInAction;

        // Dynamic-id spells use these
        final String lookupName;
        private short actionId; // mutable after resolving

        final Set<String> conflictsEnchantKeys;
        final boolean isBodyCastAllowed;

        CastSpell(String abbr,
                  String enchantKey,
                  int favorCap,
                  PlayerAction builtInAction,
                  String lookupName,
                  int fallbackActionId,
                  Set<String> conflictsEnchantKeys,
                  boolean isBodyCastAllowed) {
            this.abbr = abbr;
            this.enchantKey = enchantKey;
            this.favorCap = favorCap;
            this.builtInAction = builtInAction;
            this.lookupName = lookupName;
            this.actionId = (short) fallbackActionId;
            this.conflictsEnchantKeys = (conflictsEnchantKeys == null) ? Collections.<String>emptySet() : conflictsEnchantKeys;
            this.isBodyCastAllowed = isBodyCastAllowed;
        }

        boolean usesDynamicActionId() {
            return builtInAction == null && lookupName != null && !lookupName.trim().isEmpty();
        }

        String lookupNameLower() {
            return (lookupName == null) ? null : lookupName.toLowerCase(Locale.US);
        }

        short getActionId() {
            return actionId;
        }

        void setActionId(short newId) {
            if (newId > 0) this.actionId = newId;
        }

        PlayerAction getPlayerAction() {
            if (builtInAction != null) return builtInAction;
            // For dynamic spells: build from resolved actionId (or fallback if not resolved)
            return new PlayerAction("", actionId, PlayerAction.ANYTHING);
        }

        static CastSpell getByAbbr(String abbr) {
            if (abbr == null) return null;
            String a = abbr.trim();
            if (a.isEmpty()) return null;
            for (CastSpell s : values()) {
                if (s.abbr.equalsIgnoreCase(a)) return s;
            }
            return null;
        }
    }

    private enum InputKey implements Bot.InputKey {
        on("Toggle bot on/off (casting is OFF by default when enabling)", ""),
        cast("Toggle item/ground casting on/off (waits for each cast to finish; auto-enables bot)", ""),
        addwin("Add inventory window under mouse cursor (reads selected items from it)", ""),
        clearwins("Clear added windows", ""),
        list("List configured enchants", ""),
        use("Set active compare enchant OR select a spell (if matches a spell abbreviation)", "key|spellAbbr"),
        scan("Scan selected items once and print comparison", ""),
        debug("Toggle debug logging", ""),
        additem("Add world object under cursor (ground target) to casting list", ""),
        itemremove("Remove world object under cursor (ground target) from casting list", ""),
        skiller("Toggle body-target casting mode (casts selected spell on your body)", ""),
        spell("Set spell to cast (woa/coc/lt/botd/harden/venom/titanforged/efficiency/bagholding/expand/phasing/rottingtouch/bloodthirst/frostbrand/flamingaura/ltoken/bless/dispel/wov)", "abbr"),
        p("Toggle repeating praying on altar under cursor. Optional arg is how many prayers to send each cycle.", "[batchCount]"),
        pt("Set pray timeout (milliseconds) used by p", "timeoutMs"),
        help("Show this help in the console", "");

        private final String description;
        private final String usage;

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