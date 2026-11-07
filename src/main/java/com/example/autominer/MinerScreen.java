package com.example.autominer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class MinerScreen extends Screen {
    private ButtonWidget mainBtn;

    public MinerScreen() {
        super(Text.literal("Auto Miner"));
    }

    private static Text onOff(String name, boolean v) {
        return Text.literal(name + ": " + (v ? "BẬT" : "TẮT"));
    }

    private void toggleButton(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {
        ButtonWidget[] ref = new ButtonWidget[1];
        ref[0] = ButtonWidget.builder(onOff(name, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(onOff(name, get.getAsBoolean()));
        }).dimensions(x, y, w, 20).build();
        addDrawableChild(ref[0]);
    }

    @Override
    protected void init() {
        int w = 200;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 100;
        int gap = 24;

        mainBtn = ButtonWidget.builder(onOff("Auto Mine", MinerLogic.enabled), b -> {
            MinerLogic.toggle(client);
            b.setMessage(onOff("Auto Mine", MinerLogic.enabled));
        }).dimensions(x, y, w, 20).build();
        addDrawableChild(mainBtn);
        y += gap;

        toggleButton(x, y, w, "Tự động ăn", () -> MinerLogic.autoEat, v -> MinerLogic.autoEat = v);
        y += gap;
        toggleButton(x, y, w, "Scaffold", () -> MinerLogic.autoScaffold, v -> MinerLogic.autoScaffold = v);
        y += gap;
        toggleButton(x, y, w, "An toàn lava", () -> MinerLogic.lavaSafety, v -> MinerLogic.lavaSafety = v);
        y += gap;
        toggleButton(x, y, w, "Gửi lệnh /fix", () -> MinerLogic.useFixCommand, v -> MinerLogic.useFixCommand = v);
        y += gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("Đặt Pos1"), b -> {
            MinerLogic.pos1 = AutoMinerClient.lookPos();
            AutoMinerClient.msg("pos1 = " + MinerLogic.pos1.toShortString());
        }).dimensions(x, y, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Đặt Pos2"), b -> {
            MinerLogic.pos2 = AutoMinerClient.lookPos();
            AutoMinerClient.msg("pos2 = " + MinerLogic.pos2.toShortString());
        }).dimensions(x + 102, y, 98, 20).build());
        y += gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("Xóa vùng chọn (về 3x3)"), b -> {
            MinerLogic.pos1 = null;
            MinerLogic.pos2 = null;
        }).dimensions(x, y, w, 20).build());
        y += gap + 6;

        addDrawableChild(ButtonWidget.builder(Text.literal("Đóng"), b -> close())
            .dimensions(x, y, w, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 120, 0xFFFFFFFF);
        String mode = (MinerLogic.pos1 != null && MinerLogic.pos2 != null) ? "Chế độ: vùng chọn" : "Chế độ: 3x3 trước mặt";
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(mode), this.width / 2, this.height / 2 - 108, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
  }
