/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.gui.nifty.flowlayout;

import de.lessvoid.nifty.Nifty;
import de.lessvoid.nifty.builder.*;
import de.lessvoid.nifty.controls.Parameters;
import de.lessvoid.nifty.nulldevice.NullSoundDevice;
import de.lessvoid.nifty.spi.input.InputSystem;
import de.lessvoid.nifty.spi.render.*;
import de.lessvoid.nifty.spi.time.impl.AccurateTimeProvider;
import java.lang.reflect.*;
import java.util.*;
import java.util.logging.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomScrollTest {
    @Test void expandingAndRepopulatingRowsPreservesChildOrderAndRenderMembership() throws Exception {
        RenderFont font = proxy(RenderFont.class, (m,a) -> switch (m.getName()) {
            case "getHeight" -> 16; case "getCharacterAdvance" -> 8; case "getWidth" -> a[0].toString().length() * 8; default -> null;
        });
        RenderImage image = proxy(RenderImage.class, (m,a) -> m.getReturnType() == int.class ? 1 : null);
        RenderDevice render = proxy(RenderDevice.class, (m,a) -> switch (m.getName()) {
            case "getWidth" -> 600; case "getHeight" -> 300; case "createFont" -> font; case "createImage" -> image; default -> null;
        });
        InputSystem input = proxy(InputSystem.class, (m,a) -> m.getReturnType() == boolean.class ? false : null);
        var nifty = new Nifty(render, new NullSoundDevice(), input, new AccurateTimeProvider());
        nifty.loadStyleFile("nifty-default-styles.xml"); nifty.loadControlFile("nifty-default-controls.xml");
        var screen = new ScreenBuilder("test") {{
            layer(new LayerBuilder("layer") {{
                childLayoutVertical();
                panel(new PanelBuilder("scroll") {{
                    childLayoutHorizontal(); width("590px"); height("80px");
                    panel(new PanelBuilder("#back") {{width("20px"); height("100%");}});
                    panel(new PanelBuilder("#content") {{childLayoutAbsolute(); width("550px"); height("80px");}});
                    panel(new PanelBuilder("#forward") {{width("20px"); height("100%");}});
                }});
            }});
        }}.build(nifty);
        nifty.gotoScreen("test"); nifty.update();
        var scroll = new CustomScroll(); scroll.bind(nifty, screen, screen.findElementById("scroll"), new Parameters(new Properties()));
        var errors = new ArrayList<String>();
        var logger = Logger.getLogger("de.lessvoid.nifty.elements.Element");
        var handler = new Handler() {
            public void publish(LogRecord record) {if (record.getLevel().intValue() >= Level.SEVERE.intValue()) errors.add(record.getMessage());}
            public void flush() {} public void close() {}
        };
        logger.addHandler(handler);
        try {
            for (int cycle = 0; cycle < 2; cycle++) {
                for (int index = 0; index < 20; index++) {
                    var item = new ControlBuilder("item-" + cycle + "-" + index, "label");
                    item.width("40px").height("40px"); scroll.addElement(item);
                }
                nifty.update(); nifty.update();
                var rows = screen.findElementById("#content").getChildren();
                assertEquals(2, rows.size());
                for (int row = 0; row < 2; row++) {
                    var expected = new ArrayList<String>();
                    for (int index = row; index < 20; index += 2) expected.add("item-" + cycle + "-" + index);
                    assertEquals(expected, rows.get(row).getChildren().stream().map(e -> e.getId()).toList());
                    var renderField = de.lessvoid.nifty.elements.Element.class.getDeclaredField("elementsRenderOrder");
                    renderField.setAccessible(true);
                    var rendered = (de.lessvoid.nifty.elements.Element[]) renderField.get(rows.get(row));
                    assertEquals(expected, Arrays.stream(rendered).map(e -> e.getId()).toList(), "Rendered icons diverged from layout order");
                }
                scroll.removeAll(); nifty.update(); nifty.update();
                assertTrue(screen.findElementById("#content").getChildren().isEmpty());
            }
            assertTrue(errors.isEmpty(), "Corrupt Nifty render membership: " + errors);
        } finally {logger.removeHandler(handler);}
    }
    interface Call {Object invoke(Method method, Object[] args);}
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> call.invoke(m,a)));
    }
}
