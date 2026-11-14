package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.core.IntendedStore;
import com.drehverschluss.packscope.core.PackscopeSettings;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.gui.model.ConflictView;
import com.drehverschluss.packscope.gui.model.DetailModel;
import com.drehverschluss.packscope.gui.model.DetailModel.Row;
import com.drehverschluss.packscope.gui.model.FileCategory;
import com.drehverschluss.packscope.gui.model.Line;
import com.drehverschluss.packscope.gui.model.Names;
import com.drehverschluss.packscope.gui.model.Numbers;
import com.drehverschluss.packscope.gui.model.StatusLook;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Detail screen (Konzept 5.2). Opened from the pack screen ({@code parent}) and closed with {@code setScreen(parent)}:
 * the pack screen is not initialised again then (only {@code repositionElements}), so the unsaved order, both scroll
 * positions and the folder watcher stay as they were; nothing is applied or reloaded.
 */
public final class DetailScreen extends Screen {
    private static final ResourceLocation UNKNOWN_ICON = ResourceLocation.withDefaultNamespace("textures/misc/unknown_pack.png");
    static final int HEADER = 54;
    static final int FOOTER = 26;
    static final int MESSAGE = 11;
    static final int MIN_LIST = 48;

    private final Screen parent;
    private final ResultSource source;
    @Nullable
    private String packId;
    private boolean allView;
    private String search = "";
    private FileCategory filter = FileCategory.ALL;
    private final Map<String, Map<String, Boolean>> toggles = new HashMap<>();
    private final Map<String, Double> scrolls = new HashMap<>();
    @Nullable
    private String selectedRow;
    @Nullable
    private Component message;
    private int messageColor = 0xFFA0A0A0;

    @Nullable
    private DetailModel model;
    private int modelGeneration = -1;
    @Nullable
    private ScanResult modelResult;
    private final TexturePreview preview = new TexturePreview();

    private RowList list;
    private EditBox searchBox;
    private Button packTab;
    private Button allTab;
    private Button filterButton;
    private Button markAllButton;
    private Button exportButton;
    private int previewSize;

    /** @param packId pack of the "this pack" view, null opens the "all packs" view */
    public DetailScreen(Screen parent, ResultSource source, @Nullable String packId) {
        super(Component.translatable("packscope.detail.title"));
        this.parent = parent;
        this.source = source;
        this.packId = packId;
        this.allView = packId == null;
    }

    // ------------------------------------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int y = 30;
        int x = 8;
        packTab = addRenderableWidget(Button.builder(Component.translatable("packscope.detail.view.pack"), b -> setView(false))
            .bounds(x, y, tabWidth("packscope.detail.view.pack"), 20).build());
        x += packTab.getWidth() + 2;
        allTab = addRenderableWidget(Button.builder(Component.translatable("packscope.detail.view.all"), b -> setView(true))
            .bounds(x, y, tabWidth("packscope.detail.view.all"), 20).build());
        x += allTab.getWidth() + 6;
        int filterW = 0;
        for (FileCategory c : FileCategory.values()) filterW = Math.max(filterW, font.width(filterLabel(c)) + 10);
        int searchW = Math.max(60, width - 8 - filterW - 4 - x);
        searchBox = addRenderableWidget(new EditBox(font, x, y, searchW, 20, Component.translatable("packscope.detail.search")));
        searchBox.setHint(Component.translatable("packscope.detail.search").withColor(0x808080));
        searchBox.setMaxLength(200);
        searchBox.setValue(search);
        searchBox.setResponder(s -> {
            if (!s.equals(search)) {
                search = s;
                rebuildRows(false);
            }
        });
        filterButton = addRenderableWidget(Button.builder(filterLabel(filter), b -> {
            filter = Screen.hasShiftDown() ? FileCategory.values()[(filter.ordinal() + FileCategory.values().length - 1) % FileCategory.values().length]
                : filter.next();
            b.setMessage(filterLabel(filter));
            rebuildRows(false);
        }).bounds(width - 8 - filterW, y, filterW, 20).tooltip(Tooltip.create(Component.translatable("packscope.detail.filter.tooltip"))).build());

        list = addRenderableWidget(new RowList(this, 8, HEADER, width - 16, 10, new RowList.Listener() {
            @Override
            public void toggle(Row row) {
                Map<String, Boolean> t = toggles.computeIfAbsent(viewKey(), k -> new HashMap<>());
                t.put(row.id(), !row.expanded());
                rebuildRows(false);
            }

            @Override
            public void action(Row row) {
                runAction(row.action());
            }

            @Override
            public void open(String id) {
                openPack(id);
            }

            @Override
            public void select(Row row) {
                selectedRow = row.id();
                list.setSelectedId(selectedRow);
                ScanResult r = source.latest();
                preview.show(r, row.previewLocation());
                layoutList();
            }
        }));

        int fy = height - FOOTER + 3;
        int bw = Math.min(150, (width - 16 - 8) / 3);
        markAllButton = addRenderableWidget(Button.builder(Component.empty(), b -> markAll()).bounds(8, fy, bw, 20).build());
        exportButton = addRenderableWidget(Button.builder(Component.translatable("packscope.detail.export"), b -> export())
            .bounds(width / 2 - bw / 2, fy, bw, 20).tooltip(Tooltip.create(Component.translatable("packscope.detail.export.tooltip"))).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width - 8 - bw, fy, bw, 20).build());

        modelGeneration = -1;
        refreshModel();
    }

    private int tabWidth(String key) {
        return Math.min(90, font.width(Component.translatable(key)) + 12);
    }

    private Component filterLabel(FileCategory c) {
        return Component.translatable("packscope.detail.filter", Component.translatable(c.key()));
    }

    /** List height: everything between header and footer, minus the message line and the preview. */
    private void layoutList() {
        int bottom = height - FOOTER - MESSAGE;
        int avail = bottom - HEADER;
        previewSize = 0;
        if (preview.location() != null) {
            int want = settings().previewSize();
            int max = avail - MIN_LIST - TexturePreview.height(0);
            previewSize = Math.max(8, Math.min(want, max));
            avail -= TexturePreview.height(previewSize);
        }
        list.setBounds(8, HEADER, width - 16, Math.max(RowList.ROW_HEIGHT, avail));
    }

    private PackscopeSettings settings() {
        UiPlatform p = PackscopeUi.platform();
        return p == null ? PackscopeSettings.DEFAULTS : p.settings();
    }

    // ------------------------------------------------------------------------------------------------ model

    private String viewKey() {
        return allView || packId == null ? "*all" : packId;
    }

    private void setView(boolean all) {
        if (all == allView) return;
        if (!all && packId == null) return;
        saveScroll();
        allView = all;
        selectedRow = null;
        preview.close();
        modelGeneration = -1;
        refreshModel();
    }

    private void openPack(String id) {
        saveScroll();
        packId = id;
        allView = false;
        selectedRow = null;
        preview.close();
        modelGeneration = -1;
        refreshModel();
    }

    private void saveScroll() {
        if (list != null) scrolls.put(viewKey(), list.scroll());
    }

    /** Rebuilds the model if the result changed (new order, marking). */
    private void refreshModel() {
        ScanResult r = source.latest();
        if (modelGeneration == source.generation() && r == modelResult && model != null) return;
        boolean sameView = model != null && modelResult != null;
        modelGeneration = source.generation();
        modelResult = r;
        if (r == null) {
            model = null;
        } else if (allView || packId == null) {
            model = DetailModel.forAll(r, PackscopeUi.language());
        } else {
            model = DetailModel.forPack(r, packId, source.intended().snapshot().markings(), PackscopeUi.language());
        }
        rebuildRows(!sameView);
        if (preview.location() != null && r != null) {
            // texture rows keep their preview, but read from the new result
            String loc = preview.location();
            preview.close();
            preview.show(r, loc);
        }
    }

    private void rebuildRows(boolean restoreScroll) {
        double keep = list.scroll();
        if (model == null) {
            list.setRows(List.of());
        } else {
            list.setRows(model.flatten(toggles.getOrDefault(viewKey(), Map.of()), search, filter));
        }
        list.setSelectedId(selectedRow);
        layoutList();
        list.setScroll(restoreScroll ? scrolls.getOrDefault(viewKey(), 0.0) : keep);
        updateButtons();
    }

    private void updateButtons() {
        packTab.active = allView && packId != null;
        allTab.active = !allView;
        filterButton.active = !allView;
        ScanResult r = source.latest();
        if (allView || packId == null || r == null) {
            markAllButton.visible = false;
            return;
        }
        markAllButton.visible = true;
        ConflictView v = ConflictView.of(r, packId);
        boolean open = v.hasOpenPairs();
        boolean anyMarked = source.intended().markings().stream()
            .anyMatch(m -> com.drehverschluss.packscope.core.PackNames.matches(m.winner(), packId)
                || com.drehverschluss.packscope.core.PackNames.matches(m.loser(), packId));
        markAllButton.setMessage(Component.translatable(open ? "packscope.detail.mark_all" : "packscope.detail.unmark_all"));
        markAllButton.setTooltip(Tooltip.create(Component.translatable(open ? "packscope.detail.mark_all.tooltip" : "packscope.detail.unmark_all.tooltip")));
        markAllButton.active = open || anyMarked;
    }

    // ------------------------------------------------------------------------------------------------ actions

    private void runAction(@Nullable DetailModel.Action a) {
        if (a == null) return;
        IntendedStore store = source.intended();
        switch (a.kind()) {
            case MARK -> store.mark(a.winner(), a.loser(), null);
            case UNMARK -> store.unmark(a.winner(), a.loser());
            case REMOVE -> {
                if (a.marking() != null) store.remove(a.marking());
            }
        }
        source.refresh();
    }

    private void markAll() {
        ScanResult r = source.latest();
        if (r == null || packId == null) return;
        IntendedStore store = source.intended();
        if (ConflictView.of(r, packId).hasOpenPairs()) store.markAllOf(packId, r);
        else store.unmarkAllOf(packId);
        source.refresh();
    }

    private void export() {
        boolean applied = source.orderApplied();
        message = Component.translatable("packscope.detail.export.running");
        messageColor = 0xFFA0A0A0;
        source.exportReport().whenComplete((Path md, Throwable e) -> minecraft.execute(() -> {
            if (e != null) {
                message = Component.translatable("packscope.detail.export.failed");
                messageColor = 0xFFFF5555;
            } else {
                message = Component.translatable(applied ? "packscope.detail.export.done" : "packscope.detail.export.done_unapplied",
                    md.getFileName().toString());
                messageColor = 0xFF55FF55;
            }
        }));
    }

    // ------------------------------------------------------------------------------------------------ frame

    @Override
    public void tick() {
        refreshModel();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refreshModel();
        super.render(g, mouseX, mouseY, partialTick);
        renderHead(g);
        int bottom = height - FOOTER - MESSAGE;
        if (preview.location() != null) {
            preview.render(g, font, 8, bottom - TexturePreview.height(previewSize) + 2, width - 16, previewSize);
        }
        if (message != null) g.drawString(font, Texts.cut(font, message, width - 16), 8, bottom + 2, messageColor, false);
        else if (source.pending()) {
            g.drawString(font, Component.translatable("packscope.status.checking"), 8, bottom + 2, 0xFFA0A0A0, false);
        }
    }

    private void renderHead(GuiGraphics g) {
        ScanResult r = source.latest();
        String lang = PackscopeUi.language();
        if (allView || packId == null) {
            g.drawString(font, Component.translatable("packscope.detail.all_title"), 8, 6, 0xFFFFFFFF, false);
            if (r != null) {
                Component sum = Component.translatable("packscope.detail.all_summary", Numbers.format(r.conflictCount(), lang),
                    Numbers.format(r.intendedCount(), lang), Numbers.format(r.coveredPacks().size(), lang));
                g.drawString(font, Texts.cut(font, sum, width - 16), 8, 17, 0xFFA0A0A0, false);
            } else {
                g.drawString(font, Component.translatable("packscope.status.checking"), 8, 17, 0xFFA0A0A0, false);
            }
            return;
        }
        ResourceLocation icon = source.icon(packId);
        g.blit(icon == null ? UNKNOWN_ICON : icon, 8, 4, 0, 0, 22, 22, 22, 22);
        Names names = r == null ? null : new Names(r);
        Component title = names == null ? Component.literal(packId) : Texts.component(Line.of("packscope.detail.pack_row", names.display(packId)));
        g.drawString(font, Texts.cut(font, title, width - 44), 34, 5, 0xFFFFFFFF, false);
        ScanResult.PackSummary s = r == null ? null : r.summary(packId);
        int x = 34;
        if (s != null) {
            StatusLook look = StatusLook.of(s.status(), PackscopeSettings.DEFAULTS);
            if (look != null) {
                StatusIcons.draw(g, look, x, 16);
                x += StatusIcons.SIZE + 3;
            }
            Component status = Texts.component(Line.of(StatusLook.tone(s.status()), StatusLook.key(s.status())));
            g.drawString(font, Texts.cut(font, status, width - x - 8), x, 17, 0xFFFFFFFF, false);
        } else {
            g.drawString(font, Component.translatable(r == null ? "packscope.status.checking" : "packscope.detail.not_active"), x, 17,
                0xFFA0A0A0, false);
        }
    }

    @Override
    public void resize(net.minecraft.client.Minecraft mc, int w, int h) {
        saveScroll();
        String s = search;
        super.resize(mc, w, h);
        search = s;
        list.setScroll(scrolls.getOrDefault(viewKey(), 0.0));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        preview.close();
    }

    // ------------------------------------------------------------------------------------------------ state

    public RowList list() {
        return list;
    }

    public TexturePreview preview() {
        return preview;
    }

    public boolean allView() {
        return allView;
    }

    @Nullable
    public String packId() {
        return packId;
    }
}
