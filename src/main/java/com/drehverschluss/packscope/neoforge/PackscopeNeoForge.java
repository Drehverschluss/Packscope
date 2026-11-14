package com.drehverschluss.packscope.neoforge;

import com.drehverschluss.packscope.Packscope;
import com.drehverschluss.packscope.core.ModNames;
import com.drehverschluss.packscope.core.PackOrder;
import com.drehverschluss.packscope.core.PackscopeService;
import com.drehverschluss.packscope.core.PackscopeSettings;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.core.minecraft.PackOrders;
import com.drehverschluss.packscope.core.minecraft.ReportReloadListener;
import com.drehverschluss.packscope.gui.HeaderButton;
import com.drehverschluss.packscope.gui.MenuStateHolder;
import com.drehverschluss.packscope.gui.PackscopeUi;
import com.drehverschluss.packscope.gui.UiPlatform;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge side of the client: config, client commands, reload listener, mod names, paths. */
public final class PackscopeNeoForge {
    private static PackscopeNeoForge instance;

    private final PackscopeService service;
    private final ModNames modNames = new NeoForgeModNames();

    private PackscopeNeoForge(ModContainer container) {
        String versions = "Packscope " + container.getModInfo().getVersion() + " · Minecraft "
            + SharedConstants.getCurrentVersion().getName() + " · NeoForge "
            + ModList.get().getModContainerById("neoforge").map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        this.service = new PackscopeService(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get().resolve(INTENDED_FILE), versions);
        this.service.intended().load();
    }

    /** Intended markings, in the config folder so a modpack can ship them (Konzept 4.1). */
    public static final String INTENDED_FILE = "packscope-intended.json";

    public static void init(IEventBus modBus, ModContainer container) {
        instance = new PackscopeNeoForge(container);
        container.registerConfig(ModConfig.Type.CLIENT, PackscopeConfig.SPEC);
        // "Config" button in the mod list: NeoForge's standard screen for packscope-client.toml
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(RegisterClientReloadListenersEvent.class, e ->
            e.registerReloadListener(new ReportReloadListener(PackscopeConfig::settings, () -> instance.report(false, false),
                () -> instance.service.clearDynamicCache())));
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, instance::registerCommands);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, PackscopeNeoForge::addHeaderButton);
        PackscopeUi.install(new UiPlatform() {
            @Override
            public PackscopeService service() {
                return instance.service;
            }

            @Override
            public PackscopeSettings settings() {
                return PackscopeConfig.settings();
            }

            @Override
            public ModNames modNames() {
                return instance.modNames;
            }
        });
    }

    /** "Conflicts" button in the header of the vanilla resource pack screen (only where Packscope is active). */
    private static void addHeaderButton(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof MenuStateHolder holder && holder.packscope$state() != null) {
            event.addListener(new HeaderButton(event.getScreen(), holder.packscope$state()));
        }
    }

    public static PackscopeNeoForge get() {
        return instance;
    }

    public PackscopeService service() {
        return service;
    }

    public ModNames modNames() {
        return modNames;
    }

    private void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("packscope")
            .then(Commands.literal("report").executes(ctx -> {
                report(false, true);
                return 1;
            }))
            .then(Commands.literal("rescan").executes(ctx -> {
                report(true, true);
                return 1;
            })));
    }

    /** The current load order with the counted languages. Render thread. */
    public PackOrder currentOrder(PackscopeSettings settings) {
        List<String> languages = settings.allLanguages() ? List.of() : PackOrders.loadedLanguages();
        return PackOrders.fromRepository(Minecraft.getInstance().getResourcePackRepository(), modNames, languages);
    }

    /**
     * Takes the current order on the render thread and writes the report in the background.
     *
     * @param rescan drop the cache first
     * @param chat   report progress and result in the chat (command), otherwise only in the log (auto export)
     */
    public CompletableFuture<PackscopeService.Report> report(boolean rescan, boolean chat) {
        Minecraft mc = Minecraft.getInstance();
        PackscopeSettings settings = PackscopeConfig.settings();
        if (rescan) service.intended().load();
        PackOrder order = currentOrder(settings);
        if (chat) {
            message(Component.translatable(rescan ? "packscope.command.rescan" : "packscope.command.scanning",
                order.resourcePackCount()).withStyle(ChatFormatting.GRAY));
        }
        if (rescan) {
            service.clearCache();
            PackscopeUi.clearCache();
        }
        CompletableFuture<PackscopeService.Report> future = service.report(order, settings);
        future.whenComplete((report, error) -> mc.execute(() -> {
            if (error != null) {
                Packscope.LOGGER.error("Packscope: report failed", error);
                if (chat) message(Component.translatable("packscope.command.failed", rootMessage(error)).withStyle(ChatFormatting.RED));
            } else if (chat) {
                ScanResult r = report.result();
                message(Component.translatable("packscope.command.done", order.resourcePackCount(), r.conflictCount(),
                    r.intendedCount(), r.coveredPacks().size(), fileLink(report.markdown())));
            }
        }));
        return future;
    }

    private static Component fileLink(Path file) {
        String path = file.toString();
        return Component.literal(file.getFileName().toString()).withStyle(s -> s.withUnderlined(true)
            .withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(path))));
    }

    private static void message(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) mc.gui.getChat().addMessage(c);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
