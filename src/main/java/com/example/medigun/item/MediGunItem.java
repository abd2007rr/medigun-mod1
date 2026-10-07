package com.example.medigun.item;

import com.example.medigun.client.MediGunRenderer;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.RenderProvider;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Medi Gun: hold right-click to heal the living entity you aim at (builds ÜberCharge);
 * sneak + right-click at 100% to trigger ÜberCharge (Resistance V for you and your target).
 * All state lives in the stack's NBT (1.20.1 = NBT era, no data components).
 */
public class MediGunItem extends Item implements GeoItem {
    // ---- tuning ----
    public static final double RANGE = 8.0;          // beam length in blocks
    public static final float HEAL_PER_TICK = 0.2f;  // 4 HP/s (2 hearts/s)
    public static final float CHARGE_MAX = 100f;
    public static final float CHARGE_HURT = 0.15f;   // per tick while target is hurt (~33 s to fill)
    public static final float CHARGE_FULL = 0.05f;   // per tick while target is at full health
    public static final int UBER_TICKS = 160;        // 8 seconds

    // ---- NBT keys ----
    private static final String NBT_CHARGE = "Uber";
    private static final String NBT_UBER_END = "UberEnd";
    private static final String NBT_MODE = "Mode"; // 0 idle, 1 healing, 2 uber (drives the animation)

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.medigun.idle");
    private static final RawAnimation HEAL = RawAnimation.begin().thenLoop("animation.medigun.heal");
    private static final RawAnimation UBER = RawAnimation.begin().thenLoop("animation.medigun.uber");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Supplier<Object> renderProvider = GeoItem.makeRenderer(this);

    public MediGunItem(Settings settings) {
        super(settings);
    }

    // ================= GeckoLib =================

    @Override
    public void createRenderer(Consumer<Object> consumer) {
        consumer.accept(new RenderProvider() {
            private MediGunRenderer renderer;

            @Override
            public BuiltinModelItemRenderer getCustomRenderer() {
                if (this.renderer == null) this.renderer = new MediGunRenderer();
                return this.renderer;
            }
        });
    }

    @Override
    public Supplier<Object> getRenderProvider() {
        return this.renderProvider;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, state -> {
            ItemStack stack = state.getData(DataTickets.ITEMSTACK);
            int mode = (stack != null && stack.hasNbt()) ? stack.getNbt().getInt(NBT_MODE) : 0;
            return state.setAndContinue(mode == 2 ? UBER : mode == 1 ? HEAL : IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    // ================= Use logic =================

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (user.isSneaking()) { // sneak + right-click = ÜberCharge
            if (!world.isClient) {
                if (getCharge(stack) >= CHARGE_MAX) {
                    activateUber((ServerWorld) world, user, stack);
                } else {
                    world.playSound(null, user.getBlockPos(), SoundEvents.BLOCK_DISPENSER_FAIL,
                            SoundCategory.PLAYERS, 0.8f, 1.2f);
                }
            }
            return TypedActionResult.success(stack, world.isClient);
        }

        user.setCurrentHand(hand); // start healing (usageTick runs every tick while held)
        return TypedActionResult.consume(stack);
    }

    @Override
    public void usageTick(World world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        LivingEntity target = findTarget(user);
        if (target == null) return;

        boolean hurt = target.getHealth() < target.getMaxHealth();
        if (hurt) target.heal(HEAL_PER_TICK);

        boolean uberActive = isUberActive(stack, world);
        if (!uberActive) addCharge(stack, hurt ? CHARGE_HURT : CHARGE_FULL);

        if (remainingUseTicks % 2 == 0) spawnBeam(serverWorld, user, target, uberActive);
        if (hurt && remainingUseTicks % 10 == 0) {
            world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                    SoundCategory.PLAYERS, 0.15f, 1.8f);
        }
    }

    private void activateUber(ServerWorld world, PlayerEntity user, ItemStack stack) {
        LivingEntity target = findTarget(user);
        applyUber(user);
        if (target != null) applyUber(target);

        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putFloat(NBT_CHARGE, 0f);
        nbt.putLong(NBT_UBER_END, world.getTime() + UBER_TICKS);

        world.playSound(null, user.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        world.spawnParticles(ParticleTypes.END_ROD, user.getX(), user.getBodyY(0.5), user.getZ(), 30, 0.5, 0.8, 0.5, 0.1);
    }

    private static void applyUber(LivingEntity entity) {
        // Resistance V = 100% damage reduction (except void / starvation)
        entity.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, UBER_TICKS, 4, false, true));
    }

    @Nullable
    private static LivingEntity findTarget(LivingEntity user) {
        World world = user.getWorld();
        Vec3d eye = user.getEyePos();
        Vec3d look = user.getRotationVec(1.0f);
        Vec3d end = eye.add(look.multiply(RANGE));

        // Walls block the beam
        HitResult block = world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, user));
        double maxSq = block.getType() == HitResult.Type.MISS
                ? RANGE * RANGE : block.getPos().squaredDistanceTo(eye);

        Box box = user.getBoundingBox().stretch(look.multiply(RANGE)).expand(1.0);
        EntityHitResult hit = ProjectileUtil.raycast(user, eye, end, box,
                e -> !e.isSpectator() && e.isAlive() && e instanceof LivingEntity, maxSq);

        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }

    private static void spawnBeam(ServerWorld world, LivingEntity user, LivingEntity target, boolean uber) {
        Vec3d from = user.getEyePos().add(0, -0.25, 0).add(user.getRotationVec(1.0f).multiply(0.7));
        Vec3d to = target.getPos().add(0, target.getHeight() * 0.6, 0);
        Vec3d delta = to.subtract(from);
        int steps = Math.max(1, (int) (delta.length() / 0.4));
        Vec3d step = delta.multiply(1.0 / steps);

        DustParticleEffect dust = new DustParticleEffect(
                uber ? new Vector3f(0.4f, 0.7f, 1.0f) : new Vector3f(1.0f, 0.25f, 0.3f), 1.0f);
        for (int i = 0; i <= steps; i++) {
            Vec3d p = from.add(step.multiply(i));
            world.spawnParticles(dust, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }

    // ================= State (NBT) =================

    private static float getCharge(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return nbt == null ? 0f : nbt.getFloat(NBT_CHARGE);
    }

    private static void addCharge(ItemStack stack, float amount) {
        stack.getOrCreateNbt().putFloat(NBT_CHARGE, Math.min(CHARGE_MAX, getCharge(stack) + amount));
    }

    private static boolean isUberActive(ItemStack stack, World world) {
        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.getLong(NBT_UBER_END) > world.getTime();
    }

    /** Keeps the "Mode" tag in sync so the client picks the right animation. */
    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (world.isClient) return;
        int mode = 0;
        if (isUberActive(stack, world)) mode = 2;
        else if (selected && entity instanceof LivingEntity living && living.isUsingItem()) mode = 1;

        if (mode == 0 && !stack.hasNbt()) return;
        NbtCompound nbt = stack.getOrCreateNbt();
        if (nbt.getInt(NBT_MODE) != mode) nbt.putInt(NBT_MODE, mode);
    }

    // ================= Misc item behaviour =================

    @Override public UseAction getUseAction(ItemStack stack) { return UseAction.NONE; }
    @Override public int getMaxUseTime(ItemStack stack) { return 72000; }

    // ÜberCharge shown as the item bar (gold when ready)
    @Override public boolean isItemBarVisible(ItemStack stack) { return getCharge(stack) > 0f; }
    @Override public int getItemBarStep(ItemStack stack) { return Math.round(13f * getCharge(stack) / CHARGE_MAX); }
    @Override public int getItemBarColor(ItemStack stack) { return getCharge(stack) >= CHARGE_MAX ? 0xFFD700 : 0x4FA3FF; }

    /** Fabric API hook: don't replay the "re-equip" bob every time our NBT changes. */
    @Override
    public boolean allowNbtUpdateAnimation(PlayerEntity player, Hand hand, ItemStack oldStack, ItemStack newStack) {
        return false;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.medigun.medigun.charge", (int) getCharge(stack)).formatted(Formatting.AQUA));
        tooltip.add(Text.translatable("tooltip.medigun.hint").formatted(Formatting.GRAY));
    }
}
