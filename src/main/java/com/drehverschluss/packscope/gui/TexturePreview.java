package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.core.PackFiles;
import com.drehverschluss.packscope.core.PackIndex;
import com.drehverschluss.packscope.core.PackRef;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.gui.model.Names;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Texture preview of the detail screen (Konzept 5.2): the versions of every pack that has the texture, side by side in
 * load order (the one that reaches the game first), each read straight from the pack file (not from the atlas, which
 * only has the winner). Reading and decoding run in the background, the upload on the render thread. Textures are
 * released when another row is chosen and when the screen closes.
 */
public final class TexturePreview {
    private static final Logger LOG = LoggerFactory.getLogger("Packscope");
    /** Images wider or higher than this are not loaded. */
    public static final int MAX_SIZE = 1024;
    static final int GAP = 8;
    static final int LABEL_HEIGHT = 11;

    public enum State {LOADING, OK, TOO_BIG, BROKEN, MISSING}

    /** One pack's version of the texture. */
    public static final class Slot {
        final String packId;
        final String title;
        volatile State state = State.LOADING;
        @Nullable
        ResourceLocation texture;
        int texW, texH, frameW, frameH;

        Slot(String packId, String title) {
            this.packId = packId;
            this.title = title;
        }

        public State state() {
            return state;
        }

        public String packId() {
            return packId;
        }

        public int frameWidth() {
            return frameW;
        }

        public int frameHeight() {
            return frameH;
        }
    }

    private record Decoded(NativeImage image, int frameW, int frameH) {}

    private static int counter;

    @Nullable
    private String location;
    private final List<Slot> slots = new ArrayList<>();
    private int generation;

    @Nullable
    public String location() {
        return location;
    }

    public List<Slot> slots() {
        return slots;
    }

    /** Shows {@code loc} of every pack in {@code r} that has it (top first); null clears the preview. */
    public void show(@Nullable ScanResult r, @Nullable String loc) {
        if (loc != null && loc.equals(location)) return;
        close();
        location = loc;
        if (r == null || loc == null) return;
        int gen = generation;
        Minecraft mc = Minecraft.getInstance();
        for (PackRef ref : r.order().packs()) {
            PackIndex index = r.indexes().get(ref.id());
            if (index == null || !index.files().containsKey(loc)) continue;
            Slot slot = new Slot(ref.id(), ref.title());
            slots.add(slot);
            CompletableFuture.supplyAsync(() -> decode(ref, index, loc, slot), Util.backgroundExecutor())
                .whenComplete((d, e) -> mc.execute(() -> upload(gen, slot, d, e)));
        }
    }

    @Nullable
    private static Decoded decode(PackRef ref, PackIndex index, String loc, Slot slot) {
        try {
            byte[] png = PackFiles.read(ref, index, loc);
            if (png == null) {
                slot.state = State.MISSING;
                return null;
            }
            int[] size = pngSize(png);
            if (size == null) {
                slot.state = State.BROKEN;
                return null;
            }
            if (size[0] > MAX_SIZE || size[1] > MAX_SIZE) {
                slot.texW = size[0];
                slot.texH = size[1];
                slot.state = State.TOO_BIG;
                return null;
            }
            NativeImage image = NativeImage.read(png);
            int[] frame = {image.getWidth(), image.getHeight()};
            byte[] meta = index.files().containsKey(loc + ".mcmeta") ? PackFiles.read(ref, index, loc + ".mcmeta") : null;
            if (meta != null) frame = frameSize(meta, image.getWidth(), image.getHeight());
            return new Decoded(image, frame[0], frame[1]);
        } catch (Exception | LinkageError e) {
            LOG.debug("Packscope: preview of {} in {} failed", loc, ref.id(), e);
            slot.state = State.BROKEN;
            return null;
        }
    }

    /** Width and height from the PNG header, or null if it is no PNG. */
    @Nullable
    static int[] pngSize(byte[] b) {
        if (b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
        int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
        int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
        if (w <= 0 || h <= 0) return null;
        return new int[]{w, h};
    }

    /**
     * Size of the first frame like {@code AnimationMetadataSection.calculateFrameSize}: width/height from the
     * animation section, else a square of the smaller side. Without an animation section the whole image.
     */
    static int[] frameSize(byte[] mcmeta, int w, int h) {
        try {
            JsonObject root = JsonParser.parseString(new String(mcmeta, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("animation") || !root.get("animation").isJsonObject()) return new int[]{w, h};
            JsonObject a = root.getAsJsonObject("animation");
            int fw = a.has("width") ? a.get("width").getAsInt() : -1;
            int fh = a.has("height") ? a.get("height").getAsInt() : -1;
            int[] f;
            if (fw > 0) f = new int[]{fw, fh > 0 ? fh : h};
            else if (fh > 0) f = new int[]{w, fh};
            else f = new int[]{Math.min(w, h), Math.min(w, h)};
            return new int[]{Math.min(f[0], w), Math.min(f[1], h)};
        } catch (RuntimeException e) {
            return new int[]{w, h};
        }
    }

    private void upload(int gen, Slot slot, @Nullable Decoded d, @Nullable Throwable e) {
        if (d == null) {
            if (e != null) slot.state = State.BROKEN;
            return;
        }
        if (gen != generation) {
            d.image().close();
            return;
        }
        try {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("packscope", "preview/" + (counter++));
            DynamicTexture tex = new DynamicTexture(d.image());
            Minecraft.getInstance().getTextureManager().register(loc, tex);
            slot.texture = loc;
            slot.texW = d.image().getWidth();
            slot.texH = d.image().getHeight();
            slot.frameW = d.frameW();
            slot.frameH = d.frameH();
            slot.state = State.OK;
        } catch (RuntimeException ex) {
            d.image().close();
            slot.state = State.BROKEN;
        }
    }

    /** Frees all textures (another row, screen closed). */
    public void close() {
        generation++;
        for (Slot s : slots) {
            if (s.texture != null) Minecraft.getInstance().getTextureManager().release(s.texture);
            s.texture = null;
        }
        slots.clear();
        location = null;
    }

    /** Height the preview needs for an image size. */
    public static int height(int size) {
        return size + LABEL_HEIGHT * 2 + 4;
    }

    /** Draws the slots from (x, y), each {@code size} px, as many as fit into {@code width}. */
    public void render(GuiGraphics g, Font font, int x, int y, int width, int size) {
        if (location == null) return;
        g.drawString(font, Component.translatable("packscope.preview.title", location), x, y, 0xFFC0C0C0, false);
        int top = y + LABEL_HEIGHT;
        int slotW = Math.max(size, 56);
        int perRow = Math.max(1, (width + GAP) / (slotW + GAP));
        int cx = x;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (i >= perRow) {
                g.drawString(font, Component.translatable("packscope.preview.more", slots.size() - perRow), cx, top + size / 2, 0xFFA0A0A0, false);
                break;
            }
            if (i == 0) g.renderOutline(cx - 1, top - 1, slotW + 2, size + 2, 0xFF55FF55);
            g.fill(cx, top, cx + slotW, top + size, 0xFF1A1A1A);
            switch (s.state) {
                case OK -> {
                    float scale = (float) size / Math.max(s.frameW, s.frameH);
                    int dw = Math.max(1, Math.round(s.frameW * scale));
                    int dh = Math.max(1, Math.round(s.frameH * scale));
                    int dx = cx + (slotW - dw) / 2;
                    int dy = top + (size - dh) / 2;
                    g.blit(s.texture, dx, dy, dw, dh, 0, 0, s.frameW, s.frameH, s.texW, s.texH);
                }
                case LOADING -> centered(g, font, Component.translatable("packscope.preview.loading"), cx, top, slotW, size);
                case TOO_BIG -> centered(g, font, Component.translatable("packscope.preview.too_big", s.texW, s.texH), cx, top, slotW, size);
                case BROKEN -> centered(g, font, Component.translatable("packscope.preview.broken"), cx, top, slotW, size);
                case MISSING -> centered(g, font, Component.translatable("packscope.preview.missing"), cx, top, slotW, size);
            }
            Component name = Component.literal(Names.shorten(s.title, 30)).withColor(i == 0 ? 0x55FF55 : 0xA0A0A0);
            g.drawString(font, Texts.cut(font, name, slotW + GAP - 2), cx, top + size + 2, 0xFFFFFFFF, false);
            cx += slotW + GAP;
        }
    }

    private static void centered(GuiGraphics g, Font font, Component c, int x, int y, int w, int h) {
        g.drawString(font, Texts.cut(font, c, w - 2), x + 1, y + (h - 8) / 2, 0xFFA0A0A0, false);
    }
}
