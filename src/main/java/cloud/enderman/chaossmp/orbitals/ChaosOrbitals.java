package cloud.enderman.chaossmp.orbitals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class ChaosOrbitals extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private NamespacedKey orbitalKey;

    // Кулдауны: UUID игрока -> (Тип орбиталки -> время окончания в мс)
    private final Map<UUID, Map<OrbitalType, Long>> cooldowns = new HashMap<>();
    // Временные сущности (волки, TNT, черепа), которые нужно гарантированно убирать
    private final Set<UUID> temporaryEntities = new HashSet<>();

    // Активные задачи и падающие TNT в одном централизованном списке
    private final List<ActiveStrike> activeStrikes = new ArrayList<>();
    private final List<FallingNukeTnt> fallingNukeTnts = new ArrayList<>();
    private BukkitTask engineTask;

    public enum OrbitalType {
        WITHER_CANNON(
                "wither_cannon",
                "<gradient:#4a00e0:#8e2de2><bold>Орбиталка: Wither Cannon</bold></gradient>",
                Material.WITHER_SKELETON_SKULL,
                45,
                List.of(
                        "<gray>Вызывает в небе визер-орудие,",
                        "<gray>обстреливающее цель черепами.",
                        "",
                        "<yellow>▸ ПКМ <gray>— навести удар (до 45 бл.)",
                        "<aqua>⏳ Перезарядка: <white>45 сек."
                )
        ),
        NUKE(
                "nuke",
                "<gradient:#ff416c:#ff4b2b><bold>Орбиталка: Nuke</bold></gradient>",
                Material.TNT,
                120,
                List.of(
                        "<gray>Сбрасывает TNT с орбиты,",
                        "<gray>взрывающийся у земли.",
                        "",
                        "<yellow>▸ ПКМ <gray>— навести удар (до 50 бл.)",
                        "<aqua>⏳ Перезарядка: <white>120 сек."
                )
        ),
        STAB(
                "stab",
                "<gradient:#00c6ff:#0072ff><bold>Орбиталка: Stab</bold></gradient>",
                Material.END_ROD,
                60,
                List.of(
                        "<gray>Концентрированный орбитальный луч,",
                        "<gray>пробивающий крыши и базы вниз.",
                        "",
                        "<yellow>▸ ПКМ <gray>— пробить точку (до 45 бл.)",
                        "<aqua>⏳ Перезарядка: <white>60 сек."
                )
        ),
        WOLF(
                "wolf",
                "<gradient:#f7971e:#ffd200><bold>Орбиталка: Wolf</bold></gradient>",
                Material.BONE,
                50,
                List.of(
                        "<gray>Десантирует стаю усиленных",
                        "<gray>охотничьих волков на врага.",
                        "",
                        "<yellow>▸ ПКМ <gray>— вызвать стаю (до 40 бл.)",
                        "<aqua>⏳ Перезарядка: <white>50 сек."
                )
        );

        final String id;
        final String displayName;
        final Material material;
        final int cooldownSeconds;
        final List<String> lore;

        OrbitalType(String id, String displayName, Material material, int cooldownSeconds, List<String> lore) {
            this.id = id;
            this.displayName = displayName;
            this.material = material;
            this.cooldownSeconds = cooldownSeconds;
            this.lore = lore;
        }

        public static OrbitalType fromId(String id) {
            for (OrbitalType t : values()) {
                if (t.id.equalsIgnoreCase(id)) return t;
            }
            return null;
        }
    }

    private interface ActiveStrike {
        boolean tick(int currentTick);
    }

    private record FallingNukeTnt(Player owner, TNTPrimed tnt, int spawnedTick) {}

    @Override
    public void onEnable() {
        this.orbitalKey = new NamespacedKey(this, "orbital_type");

        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("orbitals");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        registerRecipes();
        startEngineTask();
        getLogger().info("ChaosOrbitals запущен! Доступны: Wither Cannon, Nuke, Stab, Wolf.");
    }

    @Override
    public void onDisable() {
        if (engineTask != null) {
            engineTask.cancel();
            engineTask = null;
        }
        activeStrikes.clear();
        fallingNukeTnts.clear();

        for (UUID uuid : temporaryEntities) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        temporaryEntities.clear();
        cooldowns.clear();
    }

    private void startEngineTask() {
        this.engineTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int nowTick = Bukkit.getCurrentTick();

            Iterator<ActiveStrike> strikeIt = activeStrikes.iterator();
            while (strikeIt.hasNext()) {
                ActiveStrike strike = strikeIt.next();
                if (!strike.tick(nowTick)) {
                    strikeIt.remove();
                }
            }

            Iterator<FallingNukeTnt> tntIt = fallingNukeTnts.iterator();
            while (tntIt.hasNext()) {
                FallingNukeTnt entry = tntIt.next();
                TNTPrimed tnt = entry.tnt();
                int age = nowTick - entry.spawnedTick();

                if (!tnt.isValid() || tnt.isDead() || age > 120) {
                    temporaryEntities.remove(tnt.getUniqueId());
                    if (tnt.isValid()) tnt.remove();
                    tntIt.remove();
                    continue;
                }

                Location loc = tnt.getLocation();
                Block below = loc.clone().subtract(0, 1.3, 0).getBlock();
                if (below.getType().isSolid() || tnt.isOnGround()) {
                    temporaryEntities.remove(tnt.getUniqueId());
                    tnt.remove();
                    tntIt.remove();
                    loc.getWorld().createExplosion(loc, 5.5f, true, true, entry.owner());
                }
            }
        }, 2L, 2L);
    }

    // --- ПРЕДМЕТЫ И КРАФТЫ ---

    public ItemStack createOrbitalItem(OrbitalType type) {
        ItemStack item = new ItemStack(type.material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize("<!italic>" + type.displayName));
            List<Component> loreList = new ArrayList<>();
            for (String line : type.lore) {
                loreList.add(mm.deserialize("<!italic>" + line));
            }
            meta.lore(loreList);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(orbitalKey, PersistentDataType.STRING, type.id);
            item.setItemMeta(meta);
        }
        return item;
    }

    private OrbitalType getOrbitalType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(orbitalKey, PersistentDataType.STRING);
        return id != null ? OrbitalType.fromId(id) : null;
    }

    private void registerRecipes() {
        ShapedRecipe witherRecipe = new ShapedRecipe(new NamespacedKey(this, "craft_wither_cannon"), createOrbitalItem(OrbitalType.WITHER_CANNON));
        witherRecipe.shape("OSO", "SNS", "OSO");
        witherRecipe.setIngredient('O', Material.OBSIDIAN);
        witherRecipe.setIngredient('S', Material.WITHER_SKELETON_SKULL);
        witherRecipe.setIngredient('N', Material.NETHER_STAR);
        Bukkit.addRecipe(witherRecipe);

        ShapedRecipe nukeRecipe = new ShapedRecipe(new NamespacedKey(this, "craft_nuke"), createOrbitalItem(OrbitalType.NUKE));
        nukeRecipe.shape("RTR", "TNT", "RTR");
        nukeRecipe.setIngredient('R', Material.REDSTONE_BLOCK);
        nukeRecipe.setIngredient('T', Material.TNT);
        nukeRecipe.setIngredient('N', Material.NETHER_STAR);
        Bukkit.addRecipe(nukeRecipe);

        ShapedRecipe stabRecipe = new ShapedRecipe(new NamespacedKey(this, "craft_stab"), createOrbitalItem(OrbitalType.STAB));
        stabRecipe.shape("DED", "EBE", "DED");
        stabRecipe.setIngredient('D', Material.DIAMOND);
        stabRecipe.setIngredient('E', Material.END_ROD);
        stabRecipe.setIngredient('B', Material.BLAZE_ROD);
        Bukkit.addRecipe(stabRecipe);

        ShapedRecipe wolfRecipe = new ShapedRecipe(new NamespacedKey(this, "craft_wolf"), createOrbitalItem(OrbitalType.WOLF));
        wolfRecipe.shape("BGB", "GEG", "BGB");
        wolfRecipe.setIngredient('B', Material.BONE);
        wolfRecipe.setIngredient('G', Material.GOLDEN_APPLE);
        wolfRecipe.setIngredient('E', Material.EMERALD_BLOCK);
        Bukkit.addRecipe(wolfRecipe);
    }

    @EventHandler
    public void onPlayerJoinDiscoverRecipes(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (OrbitalType type : OrbitalType.values()) {
            NamespacedKey recipeKey = new NamespacedKey(this, "craft_" + type.id);
            if (!player.hasDiscoveredRecipe(recipeKey)) {
                player.discoverRecipe(recipeKey);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe shaped)) return;

        NamespacedKey key = shaped.getKey();
        if (!key.getNamespace().equals(getName().toLowerCase(Locale.ROOT))) return;

        for (ItemStack matrixItem : event.getInventory().getMatrix()) {
            if (getOrbitalType(matrixItem) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }

        for (OrbitalType type : OrbitalType.values()) {
            if (key.getKey().equals("craft_" + type.id)) {
                event.getInventory().setResult(createOrbitalItem(type));
                return;
            }
        }
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        if (getOrbitalType(event.getInventory().getFirstItem()) != null) {
            event.setResult(null);
        }
    }

    // --- АКТИВАЦИЯ ПО ПКМ ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        OrbitalType type = getOrbitalType(item);
        if (type == null) return;

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        Map<OrbitalType, Long> playerCd = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        long endTime = playerCd.getOrDefault(type, 0L);
        if (now < endTime) {
            long left = (endTime - now) / 1000L + 1;
            player.sendActionBar(mm.deserialize("<red><bold>⏳ Перезарядка:</bold> <gray>осталось <yellow>" + left + " сек."));
            return;
        }

        RayTraceResult ray = player.rayTraceBlocks(50.0, FluidCollisionMode.NEVER);
        if (ray == null || ray.getHitBlock() == null) {
            player.sendActionBar(mm.deserialize("<red>✖ Наведите прицел на блок (макс. 50 блоков)!"));
            return;
        }

        Location target = ray.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
        playerCd.put(type, now + (type.cooldownSeconds * 1000L));

        switch (type) {
            case WITHER_CANNON -> startWitherCannon(player, target);
            case NUKE -> startNuke(player, target);
            case STAB -> startStab(player, target);
            case WOLF -> startWolf(player, target);
        }
    }

    // --- 1. WITHER CANNON ---
    private void startWitherCannon(Player owner, Location target) {
        World world = target.getWorld();
        Location skyCenter = target.clone().add(0, 22, 0);
        owner.sendActionBar(mm.deserialize("<light_purple><bold>⚡ Wither Cannon</bold> <gray>наведена на цель!"));
        world.playSound(target, Sound.ENTITY_WITHER_AMBIENT, 1.2f, 0.8f);

        activeStrikes.add(new ActiveStrike() {
            int steps = 0;

            @Override
            public boolean tick(int currentTick) {
                if (steps >= 16 || !world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
                    return false;
                }

                drawCircle(skyCenter, 3.0, Particle.SOUL_FIRE_FLAME, 16);

                if (steps % 2 == 0) {
                    Location spawnLoc = skyCenter.clone().add(
                            (Math.random() - 0.5) * 3.0,
                            0,
                            (Math.random() - 0.5) * 3.0
                    );
                    Vector dir = target.toVector().subtract(spawnLoc.toVector()).normalize().multiply(1.6);
                    WitherSkull skull = world.spawn(spawnLoc, WitherSkull.class, s -> {
                        s.setShooter(owner);
                        s.setDirection(dir);
                        s.setVelocity(dir);
                        s.setCharged(steps % 4 == 0);
                    });
                    temporaryEntities.add(skull.getUniqueId());
                    world.playSound(spawnLoc, Sound.ENTITY_WITHER_SHOOT, 1.0f, 1.1f);

                    Bukkit.getScheduler().runTaskLater(ChaosOrbitals.this, () -> {
                        temporaryEntities.remove(skull.getUniqueId());
                        if (skull.isValid()) skull.remove();
                    }, 80L);
                }

                steps++;
                return true;
            }
        });
    }

    // --- 2. NUKE ---
    private void startNuke(Player owner, Location target) {
        World world = target.getWorld();
        Location skyCenter = target.clone().add(0, 22, 0);
        int startTick = Bukkit.getCurrentTick();
        owner.sendActionBar(mm.deserialize("<red><bold>☢ NUKE ЗАПУЩЕН!</bold> <gray>TNT падает с орбиты..."));

        activeStrikes.add(new ActiveStrike() {
            int wave = 0;
            boolean radiationApplied = false;

            @Override
            public boolean tick(int currentTick) {
                int elapsed = currentTick - startTick;

                if (!radiationApplied && elapsed >= 40) {
                    radiationApplied = true;
                    for (LivingEntity entity : target.getNearbyLivingEntities(11.0)) {
                        if (!entity.equals(owner)) {
                            entity.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 140, 1));
                            entity.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 70, 0));
                        }
                    }
                }

                if (wave < 3 && elapsed >= wave * 12) {
                    if (!world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
                        return false;
                    }

                    drawCircle(skyCenter, 4.5 - wave, Particle.FLAME, 20);
                    world.playSound(target, Sound.BLOCK_NOTE_BLOCK_BIT, 1.5f, 0.6f + (wave * 0.25f));

                    int tntCount = (wave == 2) ? 3 : 2;
                    for (int i = 0; i < tntCount; i++) {
                        double angle = (Math.PI * 2 / tntCount) * i + (wave * 0.7);
                        double radius = (wave == 2 && i == 2) ? 0.0 : 2.2;
                        Location dropLoc = skyCenter.clone().add(
                                Math.cos(angle) * radius,
                                0,
                                Math.sin(angle) * radius
                        );

                        TNTPrimed tnt = world.spawn(dropLoc, TNTPrimed.class, entity -> {
                            entity.setSource(owner);
                            entity.setFuseTicks(120);
                            entity.setYield(0.0f);
                            entity.setVelocity(new Vector(0, -0.65, 0));
                        });

                        temporaryEntities.add(tnt.getUniqueId());
                        fallingNukeTnts.add(new FallingNukeTnt(owner, tnt, currentTick));
                    }

                    wave++;
                }

                return wave < 3 || !radiationApplied;
            }
        });
    }

    // --- 3. STAB ---
    private void startStab(Player owner, Location target) {
        World world = target.getWorld();
        owner.sendActionBar(mm.deserialize("<aqua><bold>🗡 Stab</bold> <gray>пробивает цель сверху!"));
        world.strikeLightningEffect(target);

        activeStrikes.add(new ActiveStrike() {
            int depth = 15;

            @Override
            public boolean tick(int currentTick) {
                if (depth < -20) {
                    return false;
                }

                Location current = target.clone().add(0, depth, 0);
                world.spawnParticle(Particle.END_ROD, current, 12, 0.4, 0.3, 0.4, 0.02);
                world.spawnParticle(Particle.ELECTRIC_SPARK, current, 10, 0.5, 0.3, 0.5, 0.05);
                world.playSound(current, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.6f, 1.8f);

                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        Block b = current.getBlock().getRelative(x, 0, z);
                        if (isBreakable(b.getType())) {
                            b.setType(Material.AIR, false);
                        }
                    }
                }

                for (LivingEntity entity : current.getNearbyLivingEntities(2.2)) {
                    if (!entity.equals(owner)) {
                        entity.damage(12.0, owner);
                    }
                }

                depth -= 2;
                return true;
            }
        });
    }

    // --- 4. WOLF ---
    private void startWolf(Player owner, Location target) {
        World world = target.getWorld();
        owner.sendActionBar(mm.deserialize("<gold><bold>🐺 Wolf</bold> <gray>стая спущена на охоту!"));
        world.playSound(target, Sound.ENTITY_WOLF_HOWL, 1.5f, 0.9f);
        drawCircle(target, 2.5, Particle.CLOUD, 20);

        LivingEntity prey = target.getNearbyLivingEntities(14.0).stream()
                .filter(e -> !e.equals(owner) && !(e instanceof Wolf))
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(target)))
                .orElse(null);

        List<Wolf> pack = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double angle = (Math.PI * 2 / 4) * i;
            Location spawnLoc = target.clone().add(Math.cos(angle) * 1.5, 0.5, Math.sin(angle) * 1.5);
            Wolf wolf = world.spawn(spawnLoc, Wolf.class, w -> {
                w.setTamed(true);
                w.setOwner(owner);
                w.setCollarColor(DyeColor.RED);
                w.customName(mm.deserialize("<gold><bold>Орбитальный волк</bold>"));
                w.setCustomNameVisible(false);
                if (w.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    w.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(30.0);
                    w.setHealth(30.0);
                }
                w.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 450, 1));
                w.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 450, 1));
                w.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 450, 0));
                if (prey != null) {
                    w.setTarget(prey);
                }
            });
            pack.add(wolf);
            temporaryEntities.add(wolf.getUniqueId());
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Wolf wolf : pack) {
                temporaryEntities.remove(wolf.getUniqueId());
                if (wolf.isValid()) {
                    wolf.getWorld().spawnParticle(Particle.POOF, wolf.getLocation().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.02);
                    wolf.remove();
                }
            }
        }, 400L);
    }

    private boolean isBreakable(Material mat) {
        if (mat.isAir()) return false;
        return switch (mat) {
            case BEDROCK, END_PORTAL, END_PORTAL_FRAME, NETHER_PORTAL, BARRIER, COMMAND_BLOCK, REINFORCED_DEEPSLATE -> false;
            default -> true;
        };
    }

    private void drawCircle(Location center, double radius, Particle particle, int points) {
        World world = center.getWorld();
        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI * i) / points;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            world.spawnParticle(particle, center.getX() + x, center.getY(), center.getZ() + z, 1, 0, 0, 0, 0);
        }
    }

    @EventHandler
    public void onSkullExplode(EntityExplodeEvent event) {
        temporaryEntities.remove(event.getEntity().getUniqueId());
    }

    // --- АДМИН-КОМАНДА ВЫДАЧИ ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("chaosorbitals.admin")) {
            sender.sendMessage(mm.deserialize("<red>У вас нет прав на эту команду."));
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            OrbitalType type = OrbitalType.fromId(args[1]);
            if (type == null) {
                sender.sendMessage(mm.deserialize("<red>Неизвестный тип! Доступные: wither_cannon, nuke, stab, wolf"));
                return true;
            }

            Player targetPlayer = (args.length >= 3) ? Bukkit.getPlayer(args[2]) : (sender instanceof Player p ? p : null);
            if (targetPlayer == null) {
                sender.sendMessage(mm.deserialize("<red>Укажите онлайн-игрока!"));
                return true;
            }

            targetPlayer.getInventory().addItem(createOrbitalItem(type));
            sender.sendMessage(mm.deserialize("<green>Выдана орбиталка <yellow>" + type.id + " <green>игроку <white>" + targetPlayer.getName()));
            return true;
        }

        sender.sendMessage(mm.deserialize("<yellow>Использование: <white>/orbitals give <wither_cannon|nuke|stab|wolf> [игрок]"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("chaosorbitals.admin")) return List.of();
        if (args.length == 1) return List.of("give");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return List.of("wither_cannon", "nuke", "stab", "wolf");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return null;
        }
        return List.of();
    }
}
