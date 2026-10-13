/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state;

import de.lessvoid.nifty.Nifty;
import de.lessvoid.nifty.builder.*;
import de.lessvoid.nifty.controls.Label;
import de.lessvoid.nifty.controls.label.builder.LabelBuilder;
import de.lessvoid.nifty.nulldevice.NullSoundDevice;
import de.lessvoid.nifty.spi.input.InputSystem;
import de.lessvoid.nifty.spi.render.*;
import de.lessvoid.nifty.spi.time.impl.AccurateTimeProvider;
import java.lang.reflect.*;
import java.util.ListResourceBundle;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.tools.convert.map.GameLevel;
import static org.junit.jupiter.api.Assertions.*;

class ObjectivePanelTest {
    @Test void instructionIsDisplayedAlongsideBriefingAndSurvivesCloseAndReopen() throws Exception {
        var f = new Fixture();
        f.controller.showObjective(8);
        assertTrue(f.panel().isVisible());
        assertEquals("Convert the target.", f.text("scriptedObjective"));
        assertEquals("Original briefing", f.text("mainObjective"));
        assertEquals("First subgoal", f.text("subObjective1"));
        f.controller.toggleObjective();
        assertFalse(f.panel().isVisible());
        f.controller.toggleObjective();
        assertTrue(f.panel().isVisible());
        assertEquals("Convert the target.", f.text("scriptedObjective"));
        f.controller.showObjective(9);
        assertEquals("Next instruction", f.text("scriptedObjective"));
    }

    @Test void missingAndInvalidTextCannotEraseAValidInstructionOrOpenThePanel() throws Exception {
        var f = new Fixture();
        f.controller.showObjective(8);
        f.controller.toggleObjective();
        for (int id : new int[]{-1, 32768, 123}) f.controller.showObjective(id);
        assertFalse(f.panel().isVisible());
        f.controller.toggleObjective();
        assertEquals("Convert the target.", f.text("scriptedObjective"));
    }

    @Test void briefingWorksWithoutScriptTextAndResumableCleanupRetainsInstruction() throws Exception {
        var f = new Fixture();
        f.controller.toggleObjective();
        assertFalse(f.panel().findElementById("scriptedObjectivePanel").isVisible());
        f.controller.showObjective(8);
        f.controller.cleanup();
        assertEquals(8, field("displayedObjectiveId").get(f.controller));
        assertNull(field("displayedObjectiveId").get(new Fixture().controller));
        assertNull(field("objectiveLevel").get(f.controller));
        assertFalse(f.nifty.getResourceBundles().containsKey("level"));
    }

    @Test void instructionReceivedWhileHudIsDisabledCannotEraseValidatedText() throws Exception {
        var f = new Fixture();
        f.controller.showObjective(8);
        f.controller.cleanup();
        f.controller.showObjective(123);
        assertEquals(8, field("displayedObjectiveId").get(f.controller));
        assertEquals(java.util.List.of(123), java.util.List.copyOf((java.util.Deque<?>) field("pendingObjectives").get(f.controller)));
    }

    @Test void orderedUpdatesWhileHudIsDisabledKeepLastValidInstruction() throws Exception {
        var f = new Fixture();
        Object level = field("objectiveLevel").get(f.controller);
        var dictionary = f.nifty.getResourceBundles().get("level");
        f.controller.showObjective(8);
        f.controller.cleanup();
        f.controller.showObjective(9);
        f.controller.showObjective(123);
        field("objectiveLevel").set(f.controller, level);
        f.nifty.getResourceBundles().put("level", dictionary);
        var restore = PlayerScreenController.class.getDeclaredMethod("restoreObjectives");
        restore.setAccessible(true);
        restore.invoke(f.controller);
        assertEquals("Next instruction", f.text("scriptedObjective"));
        assertEquals(9, field("displayedObjectiveId").get(f.controller));
        assertTrue(((java.util.Deque<?>) field("pendingObjectives").get(f.controller)).isEmpty());
    }

    static Field field(String name) throws Exception {
        Field field = PlayerScreenController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static class Fixture {
        final Nifty nifty;
        final PlayerScreenController controller;
        Fixture() throws Exception {
            RenderFont font = proxy(RenderFont.class, (m,a) -> switch (m.getName()) {
                case "getHeight" -> 16;
                case "getCharacterAdvance" -> 8;
                case "getWidth" -> a[0].toString().length() * 8;
                default -> null;
            });
            RenderImage image = proxy(RenderImage.class, (m,a) -> m.getReturnType() == int.class ? 1 : null);
            RenderDevice render = proxy(RenderDevice.class, (m,a) -> switch (m.getName()) {
                case "getWidth" -> 1280;
                case "getHeight" -> 800;
                case "createFont" -> font;
                case "createImage" -> image;
                default -> null;
            });
            InputSystem input = proxy(InputSystem.class, (m,a) -> m.getReturnType() == boolean.class ? false : null);
            nifty = new Nifty(render, new NullSoundDevice(), input, new AccurateTimeProvider());
            nifty.loadStyleFile("nifty-default-styles.xml");
            nifty.loadControlFile("nifty-default-controls.xml");
            nifty.addResourceBundle("level", new ListResourceBundle() {
                protected Object[][] getContents() {
                    return new Object[][]{{"8", "Convert the target."}, {"9", "Next instruction"}};
                }
            });
            new ScreenBuilder(PlayerScreenController.SCREEN_HUD_ID) {{
                layer(new LayerBuilder("layer") {{
                    childLayoutVertical();
                    panel(new PanelBuilder("objective") {{
                        childLayoutVertical(); width("100%"); height("100%"); visible(false);
                        panel(new PanelBuilder("scriptedObjectivePanel") {{
                            childLayoutVertical(); visible(false);
                            control(new LabelBuilder("scriptedObjective", ""));
                        }});
                        for (String id : new String[]{"mainObjective", "subObjective1", "subObjective2", "subObjective3"})
                            control(new LabelBuilder(id, ""));
                    }});
                }});
            }}.build(nifty);
            nifty.gotoScreen(PlayerScreenController.SCREEN_HUD_ID);
            nifty.update();
            controller = new PlayerScreenController(null, nifty);
            GameLevel level = new GameLevel();
            Field bundle = GameLevel.class.getDeclaredField("resourceBundle");
            bundle.setAccessible(true);
            bundle.set(level, new ListResourceBundle() {
                protected Object[][] getContents() {
                    return new Object[][]{{"0", "Level"}, {"2", "Original briefing"}, {"3", "First subgoal"}, {"4", ""}, {"5", ""}};
                }
            });
            field("objectiveLevel").set(controller, level);
        }
        de.lessvoid.nifty.elements.Element panel() {
            return nifty.getScreen(PlayerScreenController.SCREEN_HUD_ID).findElementById("objective");
        }
        String text(String id) { return panel().findNiftyControl(id, Label.class).getText(); }
    }
    interface Call { Object invoke(Method method, Object[] args); }
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> call.invoke(m,a)));
    }
}
