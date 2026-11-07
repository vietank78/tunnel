package com.example.autominer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class MinerLogic {
    public static boolean enabled = false;
    public static boolean useFixCommand = false;
    public static boolean autoEat = true;
    public static boolean autoScaffold = true;
    public static boolean lavaSafety = true;
    public static BlockPos pos1 = null;
    public static BlockPos pos2 = null;

    private static final int FOOD_THRESHOLD = 14;
    private static final float DURABILITY_THRESHOLD = 30f;
    private static final double REACH_SQ = 4.5 * 4.5;

    private static int toolSlot = -1;
    private static Direction lockedFacing = Direction.NORTH;

    private static boolean eating = false;
    private static int eatTicks = 0;
    private static int eatCooldown = 0;
    private static int fixCooldown = 0;

    private MinerLogic() {}

    public static void toggle(MinecraftClient mc) {
        if (mc.player == null) return;
        if (enabled) {
            disable(mc, "Da TAT.");
            return;
        }
        int slot = findPickaxeSlot(mc.player, -1, 0f);
        if (slot < 0) {
            AutoMinerClient.msg("Khong tim thay cuc trong hotbar!");
            return;
        }
        toolSlot = slot;
        lockedFacing = mc.player.getHorizontalFacing();
        enabled = true;
        AutoMinerClient.msg("Da BAT (" + (pos1 != null && pos2 != null ? "vung chon" : "3x3 truoc mat") + ").");
    }

    private static void disable(MinecraftClient mc, String reason) {
        enabled = false;
        if (eating) stopEating(mc);
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        AutoMinerClient.msg(reason);
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (!enabled || p == null || w == null || im == null) return;

        if (fixCooldown > 0) fixCooldown--;
        if (eatCooldown > 0) eatCooldown--;

        if (!autoEat && eating) stopEating(mc);
        if (autoEat && handleEating(mc, p, im)) return;

        if (!handleDurability(mc, p)) return;

        PlayerInventory inv = p.getInventory();
        if (inv.getSelectedSlot() != toolSlot) inv.setSelectedSlot(toolSlot);

        BlockPos below = p.getBlockPos().down();
        if (autoScaffold && !p.getAbilities().flying && w.getBlockState(below).isAir()) {
            im.cancelBlockBreaking();
            if (placeAt(mc, p, w, im, below)) return;
        }

        BlockPos target = null;
        Vec3d eye = p.getEyePos();
        for (BlockPos pos : region(p)) {
            BlockState s = w.getBlockState(pos);
            if (s.isAir() || s.getBlock() instanceof FluidBlock) continue;
            if (s.getHardness(w, pos) < 0) continue;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > REACH_SQ) continue;

            BlockPos lava = lavaSafety ? findLavaNear(w, pos) : null;
            if (lava != null) {
                im.cancelBlockBreaking();
                if (!placeAt(mc, p, w, im, lava)) {
                    disable(mc, "Gap lava nhung khong co block/cho dat! Da dung.");
                }
                return;
            }
            target = pos;
            break;
        }

        if (target == null) {
            im.cancelBlockBreaking();
            return;
        }

        Vec3d c = Vec3d.ofCenter(target);
        aim(p, c);
        Direction face = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        im.updateBlockBreakingProgress(target, face);
        p.swingHand(Hand.MAIN_HAND);
    }

    private static List<BlockPos> region(ClientPlayerEntity p) {
        List<BlockPos> list = new ArrayList<>();
        if (pos1 != null && pos2 != null) {
            int x1 = Math.min(pos1.getX(), pos2.getX()), x2 = Math.max(pos1.getX(), pos2.getX());
            int y1 = Math.min(pos1.getY(), pos2.getY()), y2 = Math.max(pos1.getY(), pos2.getY());
            int z1 = Math.min(pos1.getZ(), pos2.getZ()), z2 = Math.max(pos1.getZ(), pos2.getZ());
            for (int y = y2; y >= y1; y--)
                for (int x = x1; x <= x2; x++)
                    for (int z = z1; z <= z2; z++)
                        list.add(new BlockPos(x, y, z));
        } else {
            BlockPos o = p.getBlockPos();
            Direction side = lockedFacing.rotateYClockwise();
            for (int h = 2; h >= 0; h--)
                for (int f = 1; f <= 3; f++)
                    for (int l = -1; l <= 1; l++)
                        list.add(o.up(h).offset(lockedFacing, f).offset(side, l));
        }
        return list;
    }

    private static BlockPos findLavaNear(ClientWorld w, BlockPos pos) {
        if (w.getFluidState(pos).isIn(FluidTags.LAVA)) return pos;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (w.getFluidState(n).isIn(FluidTags.LAVA)) return n;
        }
        return null;
    }

    private static boolean placeAt(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w,
                                   ClientPlayerInteractionManager im, BlockPos target) {
        int slot = findBlockSlot(p, w);
        if (slot < 0) return false;
        Vec3d eye = p.getEyePos();
        for (Direction d : Direction.values()) {
            BlockPos support = target.offset(d);
            BlockState s = w.getBlockState(support);
            if (s.isAir() || !w.getFluidState(support).isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(support).add(
                face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            if (eye.squaredDistanceTo(hitVec) > REACH_SQ) continue;

            p.getInventory().setSelectedSlot(slot);
            aim(p, hitVec);
            BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
            ActionResult r = im.interactBlock(p, Hand.MAIN_HAND, hit);
            if (r.isAccepted()) p.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static int findBlockSlot(ClientPlayerEntity p, ClientWorld w) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isOf(Items.COBBLESTONE) || st.isOf(Items.COBBLED_DEEPSLATE)
                || st.isOf(Items.NETHERRACK) || st.isOf(Items.DIRT)) return i;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getStack(i);
            if (st.getItem() instanceof BlockItem bi) {
                Block b = bi.getBlock();
                if (b instanceof FallingBlock || b == Blocks.TNT) continue;
                if (b.getDefaultState().hasBlockEntity()) continue;
                if (b.getDefaultState().isFullCube(w, BlockPos.ORIGIN)) return i;
            }
        }
        return -1;
    }

    private static float durabilityPercent(ItemStack s) {
        if (!s.isDamageable()) return 100f;
        int max = s.getMaxDamage();
        return (max - s.getDamage()) * 100f / max;
    }

    private static int findPickaxeSlot(ClientPlayerEntity p, int exclude, float minPercent) {
        PlayerInventory inv = p.getInventory();
        int sel = inv.getSelectedSlot();
        if (sel != exclude) {
            ItemStack s = inv.getStack(sel);
            if (s.isIn(ItemTags.PICKAXES) && durabilityPercent(s) >= minPercent) return sel;
        }
        for (int i = 0; i < 9; i++) {
            if (i == exclude) continue;
            ItemStack s = inv.getStack(i);
            if (s.isIn(ItemTags.PICKAXES) && durabilityPercent(s) >= minPercent) return i;
        }
        return -1;
    }

    private static boolean handleDurability(MinecraftClient mc, ClientPlayerEntity p) {
        ItemStack tool = p.getInventory().getStack(toolSlot);
        if (!tool.isIn(ItemTags.PICKAXES)) {
            int s = findPickaxeSlot(p, -1, 0f);
            if (s < 0) { disable(mc, "Het cuc! Da dung."); return false; }
            toolSlot = s;
            return true;
        }
        float pct = durabilityPercent(tool);
        if (pct >= DURABILITY_THRESHOLD) return true;

        int alt = findPickaxeSlot(p, toolSlot, DURABILITY_THRESHOLD);
        if (alt >= 0) {
            toolSlot = alt;
            AutoMinerClient.msg("Doi sang cuc khac (slot " + (alt + 1) + ").");
            return true;
        }
        if (useFixCommand && fixCooldown == 0 && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendChatCommand("fix");
            fixCooldown = 20 * 30;
        }
        if (tool.isDamageable() && tool.getMaxDamage() - tool.getDamage() <= 2) {
            disable(mc, "Cuc sap hong! Da dung.");
            return false;
        }
        return true;
    }

    private static boolean isGoodFood(ItemStack s) {
        if (!s.contains(DataComponentTypes.FOOD)) return false;
        return !(s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.SPIDER_EYE)
            || s.isOf(Items.POISONOUS_POTATO) || s.isOf(Items.PUFFERFISH)
            || s.isOf(Items.CHORUS_FRUIT));
    }

    private static boolean handleEating(MinecraftClient mc, ClientPlayerEntity p, ClientPlayerInteractionManager im) {
        int food = p.getHungerManager().getFoodLevel();
        if (eating) {
            eatTicks++;
            boolean done = !p.isUsingItem() && eatTicks > 3;
            if (done || eatTicks > 100) {
                stopEating(mc);
                eatCooldown = 5;
                return false;
            }
            mc.options.useKey.setPressed(true);
            return true;
        }
        if (food <= FOOD_THRESHOLD && eatCooldown == 0) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < 9; i++) {
                if (isGoodFood(inv.getStack(i))) {
                    im.cancelBlockBreaking();
                    inv.setSelectedSlot(i);
                    eating = true;
                    eatTicks = 0;
                    mc.options.useKey.setPressed(true);
                    return true;
                }
            }
        }
        return false;
    }

    private static void stopEating(MinecraftClient mc) {
        eating = false;
        mc.options.useKey.setPressed(false);
        if (mc.player != null && toolSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(toolSlot);
        }
    }

    private static void aim(ClientPlayerEntity p, Vec3d v) {
        Vec3d e = p.getEyePos();
        double dx = v.x - e.x, dy = v.y - e.y, dz = v.z - e.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, hd)));
    }
          }
