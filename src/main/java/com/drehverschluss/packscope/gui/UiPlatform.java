package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.core.ModNames;
import com.drehverschluss.packscope.core.PackscopeService;
import com.drehverschluss.packscope.core.PackscopeSettings;

/** What the UI needs from the loader part (NeoForge): the service, the current settings and the mod names. */
public interface UiPlatform {
    PackscopeService service();

    /** Current config values (read on the render thread). */
    PackscopeSettings settings();

    ModNames modNames();
}
