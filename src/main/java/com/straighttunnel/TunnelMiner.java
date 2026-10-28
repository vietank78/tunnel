package com.straighttunnel;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Đào đường hầm thẳng (kiểu Baritone "tunnel") với cúp 3x3.
 *
 * Cúp 3x3 phá 1 block là server phá luôn vùng 3x3 quanh nó, nên bot chỉ cần ngắm block TÂM
 * (cao hơn chân 1 block) ngay phía trước, đào rồi đi tới.
 *
 *  - Nước/lava phía trước  -> đào vòng sang bên, đi qua, rồi quay lại đúng đường cũ.
 *  - Bedrock               -> chân bị chặn thì nhảy lên bậc; hết cách thì đào vòng như nước/lava.
 *  - Máu thấp              -> ăn táo vàng (hết táo thì /shop mua).
 *  - Cúp sắp hỏng          -> /shop mua bình XP, ném xuống chân để Mending sửa.
 */
public final class TunnelMiner implements ClientModInitializer {
	// ---- cấu hình ----
	private static final float EAT_HP = 10.0f;          // máu + absorption <= giá trị này thì ăn (10 = 5 tim)
	private static final int EAT_COOLDOWN = 60;         // tick nghỉ giữa 2 lần ăn
	private static final int APPLE_BUY_CLICKS = 4;      // số lần bấm mua táo vàng trong /shop
	private static final int XP_BUY_CLICKS = 8;         // số lần bấm mua bình XP trong /shop
	private static final int LATERAL_OFFSET = 4;        // đường vòng lệch bao nhiêu block so với đường chính
	private static final int STUCK_TICKS = 80;
	private static final boolean EAT_ENCHANTED_TOO = true;
	// Trong /shop, táo vàng nằm ở danh mục con: bấm biểu tượng totem (ở giữa) trước.
	// Tìm theo TÊN (bỏ dấu, thường hoá). Sửa các từ khoá này cho khớp tên thật trong shop của bạn.
	private static final Item FOOD_CATEGORY_ICON = Items.TOTEM_OF_UNDYING;
	private static final String[] FOOD_CATEGORY_NAMES = { "totem", "do an", "thuc an", "food" };
	private static final String[] APPLE_NAMES = { "tao vang", "golden apple" };
	private static final String[] APPLE_EXCLUDE = { "phu phep", "enchanted", "than" }; // tránh táo phù phép (đắt)
	private static final String[] XP_NAMES = { "chai kinh nghiem", "kinh nghiem", "experience", "xp" };

	private enum Task { MINE, EAT, SHOP, REPAIR }
	private enum Mode { MAIN, SIDE_OUT, PASS, SIDE_BACK }

	private static boolean active = false;
	private static Task task = Task.MINE;
	private static Mode mode = Mode.MAIN;
	private static Direction mainDir = Direction.NORTH;
	private static Direction sideDir = Direction.EAST;
	private static int laneCoord = 0;
	private static int passFwdStart = 0;

	private static BlockPos lastPos = null;
	private static int stillTicks = 0;

	// eat
	private static int eatStage, eatTimer, eatCooldown, eatSwapIdx = -1;
	private static boolean shopTriedApple = false;
	// repair
	private static int repStage, repTimer, repSwapIdx = -1, repairRounds = 0;
	private static boolean shopTriedXp = false;
	// shop
	private static Item shopItem, shopCategory;
	private static String[] shopNames, shopExclude, shopCatNames;
	private static boolean shopCategoryClicked;
	private static int shopCount, shopStage, shopTimer, shopClicks;
	private static Task afterShop = Task.MINE;

	private record Plan(List<BlockPos> targets, boolean jump, boolean blocked, BlockPos floor) {}

	private static KeyMapping toggleKey;

	/** Entrypoint của mod (khai báo trong fabric.mod.json). */
	@Override
	public void onInitializeClient() {
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.straighttunnel.toggle",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			KeyMapping.Category.MISC
		));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) toggle(client);
			tick(client);
		});
	}

	// =====================================================================================
	// bật / tắt
	// =====================================================================================

	public static void toggle(Minecraft mc) {
		if (active) {
			stop(mc, "Đã tắt đào hầm.");
			return;
		}
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		mainDir = Direction.fromYRot(p.getYRot());
		laneCoord = lateral(p.blockPosition());
		mode = Mode.MAIN;
		task = Task.MINE;
		lastPos = null;
		stillTicks = 0;
		eatCooldown = 0;
		shopTriedApple = false;
		shopTriedXp = false;
		repairRounds = 0;
		eatSwapIdx = -1;
		repSwapIdx = -1;
		active = true;
		msg(mc, "Bật đào hầm 3x3 hướng " + mainDir.getName().toUpperCase() + " (nhấn K để dừng)");
	}

	public static void stop(Minecraft mc, String reason) {
		active = false;
		if (mc.options != null) {
			mc.options.keyUp.setDown(false);
			mc.options.keyJump.setDown(false);
			mc.options.keyUse.setDown(false);
		}
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		// trả lại đồ nếu đang đổi sang tay trái
		LocalPlayer p = mc.player;
		if (p != null && mc.gameMode != null && mc.screen == null) {
			if (eatSwapIdx >= 0) { swapOffhand(mc, p, eatSwapIdx); eatSwapIdx = -1; }
			if (repSwapIdx >= 0) { swapOffhand(mc, p, repSwapIdx); repSwapIdx = -1; }
		}
		if (reason != null) msg(mc, reason);
	}

	// =====================================================================================
	// vòng lặp chính
	// =====================================================================================

	public static void tick(Minecraft mc) {
		if (!active) return;
		LocalPlayer p = mc.player;
		ClientLevel w = mc.level;
		if (p == null || w == null || mc.gameMode == null) {
			active = false;
			return;
		}
		if (!p.isAlive()) {
			stop(mc, "Dừng: đã chết.");
			return;
		}
		if (eatCooldown > 0) eatCooldown--;

		switch (task) {
			case SHOP -> tickShop(mc, p);
			case EAT -> tickEat(mc, p);
			case REPAIR -> tickRepair(mc, p);
			default -> {
				if (mc.screen != null) {
					idle(mc);
					return;
				}
				tickMine(mc, p, w);
			}
		}
	}

	private static void idle(Minecraft mc) {
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
	}

	// =====================================================================================
	// ĐÀO
	// =====================================================================================

	private static void tickMine(Minecraft mc, LocalPlayer p, ClientLevel w) {
		// --- máu thấp -> ăn ---
		float hp = p.getHealth() + p.getAbsorptionAmount();
		if (hp <= EAT_HP && eatCooldown <= 0) {
			idle(mc);
			task = Task.EAT;
			eatStage = 0;
			eatTimer = 0;
			return;
		}

		// --- cúp sắp hỏng -> sửa ---
		ItemStack held = p.getMainHandItem();
		if (held.isEmpty()) {
			stop(mc, "Dừng: tay đang trống, hãy cầm cúp.");
			return;
		}
		if (held.isDamageableItem() && remaining(held) <= lowThreshold(held)) {
			idle(mc);
			beginRepair();
			return;
		}

		BlockPos feet = p.blockPosition();

		// --- chuyển chế độ đường vòng ---
		if (mode == Mode.SIDE_OUT && Math.abs(lateral(feet) - laneCoord) >= LATERAL_OFFSET) {
			mode = Mode.PASS;
			passFwdStart = fwd(feet);
		} else if (mode == Mode.PASS && Math.abs(fwd(feet) - passFwdStart) >= 3 && returnPathClear(w, feet)) {
			mode = Mode.SIDE_BACK;
		} else if (mode == Mode.SIDE_BACK && lateral(feet) == laneCoord) {
			mode = Mode.MAIN;
			msg(mc, "Đã vòng xong, đào thẳng tiếp.");
		}

		Direction cur = currentDir();
		Plan pl = plan(w, feet, cur);
		boolean hazard = fluidAhead(w, feet, cur);

		if (mode == Mode.MAIN && (hazard || pl.blocked())) {
			Direction side = chooseSide(w, feet);
			if (side == null) {
				stop(mc, "Dừng: nước/lava/bedrock chặn, không có đường vòng an toàn.");
				return;
			}
			sideDir = side;
			mode = Mode.SIDE_OUT;
			msg(mc, hazard ? "Có nước/lava phía trước -> đi vòng." : "Bedrock chặn -> đi vòng.");
			cur = currentDir();
			pl = plan(w, feet, cur);
			hazard = fluidAhead(w, feet, cur);
		}
		if (hazard) {
			stop(mc, "Dừng: nước/lava cả ở đường vòng.");
			return;
		}
		if (pl.blocked()) {
			stop(mc, "Dừng: bedrock chặn cả ở đường vòng.");
			return;
		}

		// --- khoá góc nhìn ---
		BlockPos aim = pl.targets().isEmpty() ? feet.relative(cur).above() : pl.targets().get(0);
		Vec3 eye = p.getEyePosition();
		double ax = aim.getX() + 0.5 - eye.x;
		double ay = aim.getY() + 0.5 - eye.y;
		double az = aim.getZ() + 0.5 - eye.z;
		double horiz = Math.sqrt(ax * ax + az * az);
		p.setYRot(cur.toYRot());
		p.setXRot((float) -Math.toDegrees(Math.atan2(ay, horiz)));

		if (!pl.targets().isEmpty()) {
			mc.options.keyUp.setDown(false);
			mc.options.keyJump.setDown(false);
			mc.gameMode.continueDestroyBlock(pl.targets().get(0), cur.getOpposite());
			p.swing(InteractionHand.MAIN_HAND);
			stillTicks = 0;
			return;
		}

		mc.gameMode.stopDestroyBlock();
		if (!isSolid(w, pl.floor())) {
			idle(mc);
			stop(mc, "Dừng: phía trước không có sàn (hố).");
			return;
		}
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(pl.jump());

		// --- phát hiện kẹt ---
		if (feet.equals(lastPos)) {
			if (++stillTicks > STUCK_TICKS) {
				stop(mc, "Dừng: bị kẹt, không tiến thêm được.");
			}
		} else {
			stillTicks = 0;
			lastPos = feet;
		}
	}

	/** Lập kế hoạch cho cột block ngay phía trước theo hướng d. */
	private static Plan plan(ClientLevel w, BlockPos feet, Direction d) {
		BlockPos a = feet.relative(d);
		BlockPos f = a;          // block ngang chân
		BlockPos h = a.above();     // block ngang đầu (= tâm 3x3)
		BlockPos t = a.above(2);    // hàng trên của 3x3
		BlockPos floor = a.below();

		boolean fBad = isUnbreakable(w, f);
		boolean hBad = isUnbreakable(w, h);
		boolean tBad = isUnbreakable(w, t);

		List<BlockPos> targets = new ArrayList<>();
		if (!fBad && !hBad) {
			// bình thường: tâm -> chân -> hàng trên (bỏ qua nếu là bedrock)
			if (isSolid(w, h)) targets.add(h);
			if (isSolid(w, f)) targets.add(f);
			if (!tBad && isSolid(w, t)) targets.add(t);
			return new Plan(targets, false, false, floor);
		}
		if (fBad && !hBad && !tBad) {
			// chân bị bedrock: đào h, t rồi nhảy lên đứng trên bedrock
			if (isSolid(w, h)) targets.add(h);
			if (isSolid(w, t)) targets.add(t);
			return new Plan(targets, true, false, f);
		}
		return new Plan(targets, false, true, floor);
	}

	private static boolean isSolid(ClientLevel w, BlockPos q) {
		return !w.getBlockState(q).getCollisionShape(w, q).isEmpty();
	}

	private static boolean isUnbreakable(ClientLevel w, BlockPos q) {
		BlockState s = w.getBlockState(q);
		return !s.getCollisionShape(w, q).isEmpty() && s.getDestroySpeed(w, q) < 0;
	}

	/** Có nước/lava trong vùng 3x3 của 3 cột phía trước (kèm 1 block sàn)? */
	private static boolean fluidAhead(ClientLevel w, BlockPos feet, Direction d) {
		Direction perp = d.getClockWise();
		for (int step = 1; step <= 3; step++) {
			for (int side = -1; side <= 1; side++) {
				for (int up = -1; up <= 3; up++) {
					BlockPos q = feet.relative(d, step).relative(perp, side).above(up);
					if (!w.getFluidState(q).isEmpty()) return true;
				}
			}
		}
		return false;
	}

	/** Chọn bên để đào vòng (bên không có nước/lava). */
	private static Direction chooseSide(ClientLevel w, BlockPos feet) {
		Direction[] cands = { mainDir.getClockWise(), mainDir.getCounterClockWise() };
		for (Direction side : cands) {
			boolean ok = true;
			for (int i = 0; i <= LATERAL_OFFSET + 1 && ok; i++) {
				for (int f = -2; f <= 2 && ok; f++) {
					for (int y = -1; y <= 3; y++) {
						BlockPos q = feet.relative(side, i).relative(mainDir, f).above(y);
						if (!w.getFluidState(q).isEmpty()) { ok = false; break; }
					}
				}
			}
			if (ok) return side;
		}
		return null;
	}

	/** Đường quay lại làn chính (và vùng quanh nó) đã hết nước/lava chưa? */
	private static boolean returnPathClear(ClientLevel w, BlockPos feet) {
		Direction back = sideDir.getOpposite();
		int dist = Math.abs(lateral(feet) - laneCoord);
		for (int i = -1; i <= dist + 1; i++) {
			for (int f = -2; f <= 4; f++) {
				for (int y = -1; y <= 3; y++) {
					BlockPos q = feet.relative(back, i).relative(mainDir, f).above(y);
					if (!w.getFluidState(q).isEmpty()) return false;
				}
			}
		}
		return true;
	}

	private static Direction currentDir() {
		return switch (mode) {
			case MAIN, PASS -> mainDir;
			case SIDE_OUT -> sideDir;
			case SIDE_BACK -> sideDir.getOpposite();
		};
	}

	private static int lateral(BlockPos q) {
		return mainDir.getAxis() == Direction.Axis.X ? q.getZ() : q.getX();
	}

	private static int fwd(BlockPos q) {
		return mainDir.getAxis() == Direction.Axis.X ? q.getX() : q.getZ();
	}

	// =====================================================================================
	// ĂN TÁO VÀNG
	// =====================================================================================

	private static boolean isGapple(ItemStack s) {
		return s.is(Items.GOLDEN_APPLE) || (EAT_ENCHANTED_TOO && s.is(Items.ENCHANTED_GOLDEN_APPLE));
	}

	private static void tickEat(Minecraft mc, LocalPlayer p) {
		idle(mc);
		if (mc.screen != null) return;

		switch (eatStage) {
			case 0 -> {
				if (isGapple(p.getOffhandItem())) {
					eatStage = 1;
					return;
				}
				int idx = findSlot(p, TunnelMiner::isGapple);
				if (idx < 0) {
					if (shopTriedApple) {
						stop(mc, "Dừng: máu thấp, không có táo vàng và mua ở /shop không được.");
						return;
					}
					shopTriedApple = true;
					msg(mc, "Hết táo vàng -> mở /shop mua.");
					startShop(Items.GOLDEN_APPLE, APPLE_NAMES, APPLE_EXCLUDE,
						FOOD_CATEGORY_ICON, FOOD_CATEGORY_NAMES, APPLE_BUY_CLICKS, Task.EAT);
					return;
				}
				swapOffhand(mc, p, idx);
				eatSwapIdx = idx;
				eatStage = 1;
			}
			case 1 -> {
				mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
				mc.options.keyUse.setDown(true);
				eatStage = 2;
				eatTimer = 0;
			}
			case 2 -> {
				mc.options.keyUse.setDown(true);
				eatTimer++;
				if ((!p.isUsingItem() && eatTimer > 3) || eatTimer > 80) {
					mc.options.keyUse.setDown(false);
					if (eatSwapIdx >= 0) {
						swapOffhand(mc, p, eatSwapIdx);
						eatSwapIdx = -1;
					}
					shopTriedApple = false;
					eatCooldown = EAT_COOLDOWN;
					task = Task.MINE;
				}
			}
			default -> task = Task.MINE;
		}
	}

	// =====================================================================================
	// SỬA CÚP BẰNG BÌNH XP
	// =====================================================================================

	private static boolean isXp(ItemStack s) {
		return s.is(Items.EXPERIENCE_BOTTLE);
	}

	private static void beginRepair() {
		task = Task.REPAIR;
		repStage = 0;
		repTimer = 0;
		repairRounds++;
	}

	private static void tickRepair(Minecraft mc, LocalPlayer p) {
		idle(mc);
		if (mc.screen != null) return;
		ItemStack held = p.getMainHandItem();

		switch (repStage) {
			case 0 -> {
				if (countItem(p, TunnelMiner::isXp) == 0) {
					if (shopTriedXp) {
						stop(mc, "Dừng: cúp sắp hỏng, không mua được bình XP ở /shop.");
						return;
					}
					shopTriedXp = true;
					msg(mc, "Cúp sắp hỏng -> mở /shop mua bình XP.");
					startShop(Items.EXPERIENCE_BOTTLE, XP_NAMES, null, null, null, XP_BUY_CLICKS, Task.REPAIR);
					return;
				}
				shopTriedXp = false;
				if (!isXp(p.getOffhandItem())) {
					int idx = findSlot(p, TunnelMiner::isXp);
					if (idx >= 0) {
						swapOffhand(mc, p, idx);
						repSwapIdx = idx;
					}
				}
				repStage = 1;
				repTimer = 0;
			}
			case 1 -> {
				// nhìn xuống chân rồi ném bình bằng tay trái
				p.setXRot(90.0f);
				repTimer++;
				if (!isXp(p.getOffhandItem())) {
					repStage = 2;
					repTimer = 0;
					return;
				}
				if (repTimer % 2 == 0) {
					mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
				}
				if (held.getDamageValue() <= held.getMaxDamage() / 10) {
					repStage = 2;
					repTimer = 0;
				}
			}
			case 2 -> {
				p.setXRot(90.0f);
				if (++repTimer < 25) return; // chờ nhặt hết orb XP
				if (repSwapIdx >= 0) {
					swapOffhand(mc, p, repSwapIdx);
					repSwapIdx = -1;
				}
				if (remaining(held) > lowThreshold(held) * 2) {
					repairRounds = 0;
					task = Task.MINE;
					msg(mc, "Đã sửa cúp, đào tiếp.");
				} else if (repairRounds >= 4) {
					stop(mc, "Dừng: bình XP không sửa được cúp (cúp có Mending không?).");
				} else {
					beginRepair();
				}
			}
			default -> task = Task.MINE;
		}
	}

	private static int remaining(ItemStack s) {
		return s.getMaxDamage() - s.getDamageValue();
	}

	private static int lowThreshold(ItemStack s) {
		return Math.max(40, s.getMaxDamage() / 20);
	}

	// =====================================================================================
	// /shop
	// =====================================================================================

	/**
	 * Tìm trong /shop theo TÊN hiển thị (đã bỏ dấu, không phân biệt hoa thường) vì resource pack đổi hình item.
	 * names/exclude: từ khoá của món cần mua; category/catNames: danh mục con cần bấm trước (null = không cần).
	 */
	private static void startShop(Item item, String[] names, String[] exclude,
								  Item category, String[] catNames, int clicks, Task after) {
		task = Task.SHOP;
		shopNames = names;
		shopExclude = exclude;
		shopCategory = category;
		shopCatNames = catNames;
		shopCategoryClicked = false;
		shopItem = item;
		shopCount = clicks;
		afterShop = after;
		shopStage = 0;
		shopTimer = 0;
		shopClicks = 0;
	}

	/** Bỏ dấu tiếng Việt + thường hoá để so tên không phụ thuộc dấu/hoa thường. */
	private static String norm(String s) {
		String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.replace('đ', 'd').replace('Đ', 'd');
		return n.toLowerCase(java.util.Locale.ROOT);
	}

	/** Slot shop khớp nếu đúng loại item HOẶC tên chứa từ khoá (và không chứa từ khoá loại trừ). */
	private static boolean shopMatches(ItemStack st, Item type, String[] names, String[] exclude) {
		if (st.isEmpty()) return false;
		String name = norm(st.getHoverName().getString());
		if (exclude != null) {
			for (String x : exclude) if (name.contains(x)) return false;
		}
		if (names != null) {
			for (String k : names) if (name.contains(k)) return true;
		}
		return st.is(type);
	}

	private static void tickShop(Minecraft mc, LocalPlayer p) {
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
		shopTimer++;

		switch (shopStage) {
			case 0 -> {
				if (mc.screen != null) mc.setScreen(null);
				p.connection.sendCommand("shop");
				shopStage = 1;
				shopTimer = 0;
			}
			case 1 -> {
				if (mc.screen instanceof AbstractContainerScreen<?> && !(mc.screen instanceof InventoryScreen)) {
					shopStage = 2;
					shopTimer = 0;
				} else if (shopTimer > 80) {
					stop(mc, "Dừng: /shop không mở được GUI.");
				}
			}
			case 2 -> {
				if (shopTimer < 6) return; // chờ server gửi nội dung GUI
				if (!(mc.screen instanceof AbstractContainerScreen<?> hs)) {
					shopStage = 3;
					shopTimer = 0;
					return;
				}
				AbstractContainerMenu h = hs.getMenu();
				Slot target = null;
				for (Slot s : h.slots) {
					if (s.container instanceof Inventory) continue;
					if (shopMatches(s.getItem(), shopItem, shopNames, shopExclude)) { target = s; break; }
				}
				if (target == null && shopCategory != null && !shopCategoryClicked) {
					// chưa thấy item -> bấm biểu tượng danh mục (nếu có nhiều thì lấy cái ở giữa)
					List<Slot> icons = new ArrayList<>();
					List<Slot> nonEmpty = new ArrayList<>();
					for (Slot s : h.slots) {
						if (s.container instanceof Inventory) continue;
						if (s.getItem().isEmpty()) continue;
						nonEmpty.add(s);
						if (shopMatches(s.getItem(), shopCategory, shopCatNames, null)) icons.add(s);
					}
					if (icons.isEmpty()) {
						// không khớp theo tên/loại -> lấy ô có đồ nằm ở giữa menu
						if (nonEmpty.isEmpty()) {
							stop(mc, "Dừng: menu /shop trống.");
							return;
						}
						icons = nonEmpty;
					}
					Slot icon = icons.get(icons.size() / 2);
					mc.gameMode.handleInventoryMouseClick(h.containerId, icon.index, 0, ClickType.PICKUP, p);
					shopCategoryClicked = true;
					shopTimer = 0; // chờ GUI danh mục load
					return;
				}
				if (target == null) {
					if (shopClicks == 0) {
						stop(mc, "Dừng: không thấy " + shopItem.getDefaultInstance().getHoverName().getString()
							+ " ngay trong /shop (có thể nằm trong danh mục con).");
					} else {
						shopStage = 3;
						shopTimer = 0;
					}
					return;
				}
				if (shopTimer < 4) return;
				shopTimer = 0;
				mc.gameMode.handleInventoryMouseClick(h.containerId, target.index, 0, ClickType.PICKUP, p);
				if (++shopClicks >= shopCount) {
					shopStage = 3;
					shopTimer = 0;
				}
			}
			case 3 -> {
				if (shopTimer < 5) return;
				p.closeContainer();
				shopStage = 4;
				shopTimer = 0;
			}
			case 4 -> {
				if (shopTimer < 4) return;
				final Item want = shopItem;
				if (countItem(p, s -> s.is(want)) == 0) {
					stop(mc, "Dừng: mua " + want.getDefaultInstance().getHoverName().getString() + " không được (hết tiền?).");
					return;
				}
				task = afterShop;
				eatStage = 0;
				repStage = 0;
			}
			default -> task = Task.MINE;
		}
	}

	// =====================================================================================
	// tiện ích inventory
	// =====================================================================================

	/** Tìm slot (index inventory 0..35) chứa item thoả điều kiện, -1 nếu không có. */
	private static int findSlot(LocalPlayer p, Predicate<ItemStack> pred) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			if (pred.test(inv.getItem(i))) return i;
		}
		return -1;
	}

	private static int countItem(LocalPlayer p, Predicate<ItemStack> pred) {
		int n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (pred.test(s)) n += s.getCount();
		}
		ItemStack off = p.getOffhandItem();
		if (pred.test(off)) n += off.getCount();
		return n;
	}

	/** Hoán đổi slot inventory (index 0..35) với tay trái. */
	private static void swapOffhand(Minecraft mc, LocalPlayer p, int invIdx) {
		int slotId = invIdx < 9 ? 36 + invIdx : invIdx; // hotbar = 36..44, túi đồ = 9..35
		mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slotId, 40, ClickType.SWAP, p);
	}

	private static void msg(Minecraft mc, String s) {
		if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Tunnel] " + s), true);
	}
}
