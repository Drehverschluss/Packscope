package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.gui.model.DetailModel.Row;
import com.drehverschluss.packscope.gui.model.DetailModel.RowType;
import com.drehverschluss.packscope.gui.model.Line;
import com.drehverschluss.packscope.gui.model.StatusLook;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Scrolling list of the detail screen. Draws only the rows on screen (long lists such as 1 323 language keys stay
 * smooth) and builds their components only when a row is drawn for the first time.
 */
public final class RowList extends AbstractWidget {
    public static final int ROW_HEIGHT = 12;
    private static final int INDENT = 10;
    private static final int SCROLLBAR = 6;
    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation BUTTON_HOVER = ResourceLocation.withDefaultNamespace("widget/button_highlighted");

    public interface Listener {
        void toggle(Row row);

        void action(Row row);

        void open(String packId);

        void select(Row row);
    }

    private final Screen screen;
    private final Listener listener;
    private List<Row> rows = List.of();
    private Component[] texts = new Component[0];
    private Component[] tagTexts = new Component[0];
    private double scroll;
    private boolean dragging;
    @Nullable
    private String selectedId;

    public RowList(Screen screen, int x, int y, int w, int h, Listener listener) {
        super(x, y, w, h, Component.empty());
        this.screen = screen;
        this.listener = listener;
    }

    public void setRows(List<Row> rows) {
        this.rows = List.copyOf(rows);
        this.texts = new Component[rows.size()];
        this.tagTexts = new Component[rows.size()];
        clampScroll();
    }

    public List<Row> rows() {
        return rows;
    }

    public void setSelectedId(@Nullable String id) {
        selectedId = id;
    }

    public double scroll() {
        return scroll;
    }

    public void setScroll(double s) {
        scroll = s;
        clampScroll();
    }

    public int maxScroll() {
        return Math.max(0, rows.size() * ROW_HEIGHT - height);
    }

    private void clampScroll() {
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    public void setBounds(int x, int y, int w, int h) {
        setX(x);
        setY(y);
        setWidth(w);
        setHeight(h);
        clampScroll();
    }

    private Component text(int i) {
        if (texts[i] == null) texts[i] = Texts.component(rows.get(i).text());
        return texts[i];
    }

    private Component tags(int i) {
        if (tagTexts[i] == null) {
            var c = Component.empty();
            for (Line l : rows.get(i).tags()) c.append("  ").append(Texts.component(l));
            tagTexts[i] = c;
        }
        return tagTexts[i];
    }

    private boolean scrollbarVisible() {
        return maxScroll() > 0;
    }

    private int contentRight() {
        return getX() + width - (scrollbarVisible() ? SCROLLBAR + 2 : 0);
    }

    private static Component actionLabel(Row r) {
        return Component.translatable(Objects.requireNonNull(r.action()).key());
    }

    private int actionWidth(Font font, Row r) {
        return font.width(actionLabel(r)) + 8;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        g.fill(getX(), getY(), getX() + width, getY() + height, 0x66000000);
        g.enableScissor(getX(), getY(), getX() + width, getY() + height);
        int first = (int) (scroll / ROW_HEIGHT);
        int right = contentRight();
        boolean inside = isMouseOver(mouseX, mouseY);
        Row hoverRow = null;
        for (int i = first; i < rows.size(); i++) {
            int top = getY() + i * ROW_HEIGHT - (int) scroll;
            if (top >= getY() + height) break;
            Row r = rows.get(i);
            boolean hovered = inside && mouseY >= top && mouseY < top + ROW_HEIGHT;
            if (r.id().equals(selectedId)) g.fill(getX(), top, right, top + ROW_HEIGHT, 0x60FFFFFF);
            else if (hovered && clickable(r)) g.fill(getX(), top, right, top + ROW_HEIGHT, 0x30FFFFFF);
            if (r.type() == RowType.SECTION) g.fill(getX(), top, right, top + ROW_HEIGHT, 0x40000000);
            int x = getX() + 3 + r.depth() * INDENT;
            if (r.expandable()) {
                g.drawString(font, r.expanded() ? "▼" : "▶", x, top + 2, 0xFFC0C0C0, false);
                x += 10;
            }
            if (r.status() != null) {
                StatusLook look = StatusLook.of(r.status(), com.drehverschluss.packscope.core.PackscopeSettings.DEFAULTS);
                if (look != null) StatusIcons.draw(g, look, x, top + 1);
                x += StatusIcons.SIZE + 3;
            }
            int textRight = right - 2;
            boolean buttonHovered = false;
            if (r.action() != null) {
                int bw = actionWidth(font, r);
                int bx = right - 2 - bw;
                buttonHovered = hovered && mouseX >= bx && mouseX < bx + bw;
                g.blitSprite(buttonHovered ? BUTTON_HOVER : BUTTON, bx, top, bw, ROW_HEIGHT - 1);
                g.drawCenteredString(font, actionLabel(r), bx + bw / 2, top + 2, 0xFFFFFFFF);
                textRight = bx - 4;
            }
            Component text = text(i);
            int avail = textRight - x;
            int tw = font.width(text);
            g.drawString(font, Texts.cut(font, text, avail), x, top + 2, 0xFFFFFFFF, false);
            if (!r.tags().isEmpty() && tw < avail) {
                g.drawString(font, Texts.cut(font, tags(i), avail - tw), x + tw, top + 2, 0xFFA0A0A0, false);
            }
            if (hovered && !buttonHovered) hoverRow = r;
            if (hovered && !buttonHovered && (tw > avail || !r.hover().isEmpty())) {
                List<Line> lines = new ArrayList<>();
                if (tw > avail) lines.add(r.text());
                lines.addAll(r.hover());
                screen.setTooltipForNextRenderPass(Texts.tooltip(font, lines, Math.max(200, width / 2)),
                    net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE, true);
            }
        }
        g.disableScissor();
        if (scrollbarVisible()) {
            int sx = getX() + width - SCROLLBAR;
            int total = rows.size() * ROW_HEIGHT;
            int barH = Mth.clamp(height * height / total, 16, height);
            int barY = getY() + (int) ((height - barH) * scroll / maxScroll());
            g.fill(sx, getY(), sx + SCROLLBAR, getY() + height, 0xFF000000);
            g.fill(sx, barY, sx + SCROLLBAR, barY + barH, 0xFF808080);
            g.fill(sx, barY, sx + SCROLLBAR - 1, barY + barH - 1, 0xFFC0C0C0);
        }
    }

    private static boolean clickable(Row r) {
        return r.expandable() || r.targetPack() != null || r.previewLocation() != null || r.type() == RowType.ITEM;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY) || button != 0) return false;
        if (scrollbarVisible() && mouseX >= getX() + width - SCROLLBAR) {
            dragging = true;
            dragTo(mouseY);
            return true;
        }
        int i = (int) ((mouseY - getY() + scroll) / ROW_HEIGHT);
        if (i < 0 || i >= rows.size()) return false;
        Row r = rows.get(i);
        Font font = Minecraft.getInstance().font;
        if (r.action() != null) {
            int bw = actionWidth(font, r);
            int bx = contentRight() - 2 - bw;
            if (mouseX >= bx && mouseX < bx + bw) {
                playDownSound(Minecraft.getInstance().getSoundManager());
                listener.action(r);
                return true;
            }
        }
        if (r.expandable()) {
            listener.toggle(r);
        } else if (r.targetPack() != null && (r.type() == RowType.PACK || r.type() == RowType.PAIR || r.type() == RowType.MARKING)) {
            listener.open(r.targetPack());
        } else {
            listener.select(r);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            dragTo(mouseY);
            return true;
        }
        return false;
    }

    private void dragTo(double mouseY) {
        double f = (mouseY - getY()) / Math.max(1, height);
        setScroll(f * maxScroll());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) return false;
        setScroll(scroll - scrollY * ROW_HEIGHT * 3);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
