package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.gui.model.Line;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

/** Turns {@link Line}s of the presentation logic into components. */
public final class Texts {
    private Texts() {}

    /** Top-level line: coloured by its tone (NORMAL = white). */
    public static MutableComponent component(Line line) {
        return build(line).withColor(line.tone().argb & 0xFFFFFF);
    }

    /** Nested lines keep the colour of the outer line unless they have their own tone. */
    private static MutableComponent build(Line line) {
        Object[] args = new Object[line.args().size()];
        for (int i = 0; i < args.length; i++) args[i] = arg(line.args().get(i));
        return Component.translatable(line.key(), args);
    }

    private static Object arg(Object a) {
        if (a instanceof Line l) {
            MutableComponent c = build(l);
            return l.tone() == Line.Tone.NORMAL ? c : c.withColor(l.tone().argb & 0xFFFFFF);
        }
        if (a instanceof Line.Parts p) {
            MutableComponent c = Component.empty();
            for (int i = 0; i < p.parts().size(); i++) {
                if (i > 0) c.append(", ");
                Object part = arg(p.parts().get(i));
                if (part instanceof Component pc) c.append(pc);
                else c.append(String.valueOf(part));
            }
            return c;
        }
        return a == null ? "" : String.valueOf(a);
    }

    /** Tooltip lines, long lines wrapped at {@code maxWidth}. */
    public static List<FormattedCharSequence> tooltip(Font font, List<Line> lines, int maxWidth) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (Line l : lines) out.addAll(font.split(component(l), maxWidth));
        return out;
    }

    /** Cuts a component to {@code width} pixels with "…". */
    public static FormattedCharSequence cut(Font font, Component c, int width) {
        if (font.width(c) <= width) return c.getVisualOrderText();
        String dots = "…";
        FormattedText head = font.substrByWidth(c, Math.max(0, width - font.width(dots)));
        return net.minecraft.locale.Language.getInstance().getVisualOrder(FormattedText.composite(head, Component.literal(dots)
            .withStyle(c.getStyle())));
    }
}
