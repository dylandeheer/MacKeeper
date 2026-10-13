/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import de.lessvoid.nifty.Nifty;
import de.lessvoid.nifty.builder.*;
import de.lessvoid.nifty.nulldevice.NullSoundDevice;
import de.lessvoid.nifty.spi.input.InputSystem;
import de.lessvoid.nifty.spi.render.*;
import de.lessvoid.nifty.spi.time.impl.AccurateTimeProvider;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.save.MessageSnapshots;
import toniarts.openkeeper.gui.nifty.message.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageStateTest {
    @Test void actualMessageControlsRetainOrderReadStatusSelectionAndLifetimeAcrossRepeatedLoads() throws Exception {
        var f = new Fixture();
        f.owner.addMessageIcon(SystemMessageState.MessageType.INFO, "First");
        f.owner.addMessageIcon(SystemMessageState.MessageType.FIGHT, "Second");
        f.controls().getFirst().advanceTime(20);f.controls().getLast().advanceTime(5);
        f.controls().getFirst().showMessage();
        var saved = f.owner.captureState();
        assertFalse(saved.messages().getFirst().unread());assertTrue(saved.messages().getLast().unread());
        assertEquals(0, saved.box().messageIndex());assertEquals("First", saved.box().text());
        for (int i = 0; i < 4; i++) {
            f.owner.restoreState(MessageSnapshots.decode(MessageSnapshots.encode(saved)));
            assertEquals(saved, f.owner.captureState());
        }
        f.box().dismissMessage();
        assertEquals(List.of(saved.messages().getLast()), f.owner.captureState().messages());
        assertEquals(MessageState.Box.closed(), f.owner.captureState().box());
        assertEquals(saved.messages().size(), 2);
    }
    @Test void expiryKeepsAnAlreadyOpenMessageReadableAndDismissalDoesNotRemoveTheNextIcon() throws Exception {
        var f = new Fixture();f.owner.addMessageIcon(SystemMessageState.MessageType.ALLY, "Open old message");
        f.controls().getFirst().showMessage();f.controls().getFirst().advanceTime(60);
        var expired = f.owner.captureState();assertTrue(expired.messages().isEmpty());
        assertEquals(-1, expired.box().messageIndex());assertEquals(60, expired.box().detachedMessage().ageSeconds());
        f.owner.restoreState(MessageSnapshots.decode(MessageSnapshots.encode(expired)));
        assertEquals(expired, f.owner.captureState());
        f.owner.addMessageIcon(SystemMessageState.MessageType.INFO, "New message");
        f.box().dismissMessage();assertEquals("New message", f.owner.captureState().messages().getFirst().text());
    }
    @Test void pausedOwnerDoesNotAgeAndRestoredMessagesExpireAfterOnlyTheirRemainingTime() throws Exception {
        var f = new Fixture();f.owner.addMessageIcon(SystemMessageState.MessageType.CREATURE, "Read later");
        f.controls().getFirst().advanceTime(50);f.owner.setEnabled(false);
        var saved = f.owner.captureState();
        f.owner.restoreState(MessageSnapshots.decode(MessageSnapshots.encode(saved)));
        f.states.update(120);assertEquals(saved, f.owner.captureState());
        f.owner.setEnabled(true);f.states.update(9);
        assertEquals(59, f.owner.captureState().messages().getFirst().ageSeconds());
        f.states.update(1);assertTrue(f.owner.captureState().messages().isEmpty());
    }
    @Test void plainAndFocusBoxesKeepTheirModeAndFailedNullRestoreKeepsTheCurrentBar() throws Exception {
        var f = new Fixture();f.owner.addMessageIcon(SystemMessageState.MessageType.ALLY_MSG, "Focus");
        f.box().showFocusMessage(f.controls().getFirst(), "Focus text");
        var focus = f.owner.captureState();f.owner.restoreState(MessageSnapshots.decode(MessageSnapshots.encode(focus)));
        assertEquals(focus, f.owner.captureState());
        f.box().showMessage("Plain text");
        var text = f.owner.captureState();assertEquals(MessageState.BoxMode.TEXT, text.box().mode());assertEquals(-1, text.box().messageIndex());
        f.owner.restoreState(text);assertEquals(text, f.owner.captureState());
        assertThrows(NullPointerException.class, () -> f.owner.restoreState(null));assertEquals(text, f.owner.captureState());
    }
    @Test void detachedControlStateAndTimeValidationDoNotDependOnAnEngineElement() {
        var state = new MessageState.Message(SystemMessageState.MessageType.PLAYER_EXIT, "Bye", false, 42);
        var control = SystemMessageControl.detached(state);assertTrue(control.isRemoved());
        control.advanceTime(100);assertEquals(state, control.captureState());control.dismissMessage();
        assertThrows(IllegalArgumentException.class, () -> control.advanceTime(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new MessageState.Message(state.type(), state.text(), true, -1));
    }
    static class Fixture {
        final Nifty nifty;final Main app;final SystemMessageState owner;final com.jme3.app.state.AppStateManager states;
        Fixture() throws Exception {
            RenderFont font = proxy(RenderFont.class, (m, a) -> switch (m.getName()) {
                case "getHeight" -> 16;case "getCharacterAdvance" -> 8;case "getWidth" -> a[0].toString().length() * 8;default -> null;
            });
            RenderImage image = proxy(RenderImage.class, (m, a) -> m.getReturnType() == int.class ? 1 : null);
            RenderDevice render = proxy(RenderDevice.class, (m, a) -> switch (m.getName()) {
                case "getWidth" -> 1280;case "getHeight" -> 800;case "createFont" -> font;case "createImage" -> image;default -> null;
            });
            InputSystem input = proxy(InputSystem.class, (m, a) -> m.getReturnType() == boolean.class ? false : null);
            nifty = new Nifty(render, new NullSoundDevice(), input, new AccurateTimeProvider());
            nifty.loadStyleFile("nifty-default-styles.xml");nifty.loadControlFile("nifty-default-controls.xml");
            nifty.loadControlFile("Interface/MyControls.xml");
            new ScreenBuilder("hud") {{layer(new LayerBuilder("layer") {{
                childLayoutVertical();
                panel(new PanelBuilder("systemMessages") {{childLayoutHorizontal();width("100%");height("64px");}});
                control(new ControlBuilder("messageBox", "messageBox") {{width("450px");height("250px");visible(false);}});
            }});}}.build(nifty);
            nifty.gotoScreen("hud");nifty.update();
            var constructor = Main.class.getDeclaredConstructor();constructor.setAccessible(true);app = constructor.newInstance();
            var display = new com.jme3.niftygui.NiftyJmeDisplay() {
                @Override public Nifty getNifty() {return Fixture.this.nifty;}
            };
            var field = Main.class.getDeclaredField("niftyDisplay");field.setAccessible(true);field.set(app, display);
            owner = new SystemMessageState(nifty.getScreen("hud").findElementById("systemMessages"));
            states = new com.jme3.app.state.AppStateManager(app);states.attach(owner);states.update(0);
        }
        List<SystemMessageControl> controls() {
            return nifty.getScreen("hud").findElementById("systemMessages").getChildren().stream()
                    .map(element -> element.getControl(SystemMessageControl.class)).filter(c -> c != null && !c.isRemoved()).toList();
        }
        MessageBoxControl box() {return nifty.getScreen("hud").findControl("messageBox", MessageBoxControl.class);}
    }
    interface Call {Object invoke(Method method, Object[] args);}
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (p, m, a) -> call.invoke(m, a)));
    }
}
