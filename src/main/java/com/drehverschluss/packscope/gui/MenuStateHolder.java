package com.drehverschluss.packscope.gui;

import org.jetbrains.annotations.Nullable;

/** Implemented by {@code PackSelectionScreen} through a mixin: the Packscope state of that screen (null = Packscope off there). */
public interface MenuStateHolder {
    @Nullable
    MenuState packscope$state();
}
