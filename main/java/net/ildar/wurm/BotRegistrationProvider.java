package net.ildar.wurm;

import java.util.ArrayList;
import java.util.List;

class BotRegistrationProvider {
    static List<BotRegistration> getBotList() {
        List<BotRegistration> registrations = new ArrayList<>();
        try {
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.AssistantBot"), "Assists player in various ways", "a"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ArcherBot"), "Automatically shoots at selected target with currently equipped bow. When the string breaks tries to place a new one. Deactivates on target death.", "ar"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.RMIBot"), "Remotely control other clients", "rmi"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.HealingBot"), "Heals the player's wounds with cotton found in inventory", "h"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ChopperBot"), "Automatically chops felled trees near player", "ch"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.SellerBot"), "Sells items to tokens", "s"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.MultiItemMoverBot"), "Moves many sets of items to their own containers", "mim"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ContainerItemGetterBot"), "Retrieves items from containers", "cig"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.BulkItemGetterBot"), "Automatically transfers items to player's inventory from configured bulk storages. The n-th  source item will be transferred to the n-th target item", "big"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.TreeCutterBot"), "Cuts trees", "tc"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.FlowerPlanterBot"), "Skills up player's gardening skill by planting and picking flowers in surrounding area", "fp"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.CrafterBot"), "Automatically does crafting operations using items from crafting window. New crafting operations are not starting until an action queue becomes empty. This behaviour can be disabled. ", "c"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.PileCollector"), "Collects piles of items to bulk containers. Default name for target items is \"dirt\"", "pc"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ForageStuffMoverBot"), "Moves foragable and botanizable items from your inventory to the target inventories. Optionally you can toggle the moving of rocks or rare items on and off.", "fsm"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ForesterBot"), "A forester bot. Can pick and plant sprouts, cut trees/bushes and gather the harvest in 3x3 area around player. Bot can be configured to process rectangular area of any size. Sprouts, to prevent the inventory overflow, will be put to the containers. The name of containers can be configured. Containers only in root directory of player's inventory will be taken into account. New item names can be added(harvested fruits for example) to be moved to containers too. Steppe and moss tiles will be cultivated if planting is enabled and player have shovel in his inventory. ", "fr"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.MinerBot"), "Mines rocks and smelts ores.", "m"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ImproverBot"), "Improves selected items in provided inventories. Tools searched from player's inventory. Items like water or stone searched before each improve, actual instruments searched one time before improve of the first item that must be improved with this tool. Tool for improving is determined by improve icon that you see on the right side of item row in inventory. For example improve icons for stone chisel and carving knife are equal, and sometimes bot can choose wrong tool. Use \"ci\" key to change the chosen instrument.", "i"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.GroundItemGetterBot"), "Collects items from the ground around player.", "gig"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.MeditationBot"), "Meditates on the carpet. Assumes that there are no restrictions on meditation skill.", "md"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.FarmerBot"), "Tends the fields, plants the seeds, cultivates the ground, collects harvests", "f"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.PathingBot"), "Bot that can perform pathfinding to accomplish its various tasks", "pt"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.DiggerBot"), "Does the dirty job", "d"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ForagerBot"), "Can forage, botanize, collect grass and flowers in an area surrounding player. Bot can be configured to process rectangular area of any size. Picked items, to prevent the inventory overflow, will be put to the containers. The name of containers can be configured. Containers only in root directory of player's inventory will be taken into account. Bot can be configured to drop picked items on the floor. ", "fg"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ArcheoBot"), "Investigates tiles and identifies fragments", "ac"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.ItemMoverBot"), "Moves items from your inventory to the target destination.", "im"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.PriestBot"), "For all your priestly needs", "priest"));
            registrations.add(new BotRegistration(Class.forName("net.ildar.wurm.bot.Render"), "Toggles client render subsystems (weather/sky/terrain/cave/water/particles/grass/trees) via hooks", "rnd"));

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return registrations;
    }
}
