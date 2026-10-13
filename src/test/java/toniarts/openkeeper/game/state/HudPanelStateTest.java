/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state;

import de.lessvoid.nifty.builder.*;
import de.lessvoid.nifty.controls.*;
import de.lessvoid.nifty.controls.label.builder.LabelBuilder;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.save.HudPanelSnapshots;
import toniarts.openkeeper.gui.nifty.CustomTabGroupControl;
import toniarts.openkeeper.view.HudPanelState;
import static org.junit.jupiter.api.Assertions.*;

class HudPanelStateTest {
    @Test void actualPanelsRetainObjectiveSubtitleCollapsedPanelTabAndConfirmationAcrossLoads() throws Exception {
        var f = new Fixture();var c = f.controller;
        c.showObjective(8);c.toggleObjective();c.togglePanel();f.tabs().setSelectedTabIndex(1);
        c.lastUpdate = .125f;c.setCinematicText(9, false, 0);
        c.pauseMenuNavigate("CONFIRMATION", "QUIT", "Exit game", "quitToOS()");c.setPause(true);
        var saved = c.captureHudState();assertFalse(saved.objectiveVisible());assertEquals(1, saved.selectedTab());
        assertEquals(HudPanelState.Destination.DESKTOP, saved.menu().destination());assertTrue(saved.menu().visible());
        var selections = new int[1];
        f.nifty.subscribe(f.nifty.getScreen("hud"), "tabs-hud", TabSelectedEvent.class, (topic, event) -> selections[0]++);
        for (int i = 0; i < 4; i++) {
            c.togglePanel();f.tabs().setSelectedTabIndex(0);c.showObjective(9);
            c.pauseMenuNavigate("MAIN", null, null, null);c.setPause(false);
            selections[0] = 0;c.restoreHudState(HudPanelSnapshots.decode(HudPanelSnapshots.encode(saved)));
            assertEquals(saved, c.captureHudState());assertEquals(0, selections[0]);
            f.nifty.update();assertEquals(0, selections[0]);
            assertEquals("Exit game", f.nifty.getScreen("hud").findElementById("optionsMenu").findNiftyControl("#title", Label.class).getText());
            assertEquals("Convert the target.", f.text("scriptedObjective"));
            assertEquals("Convert the target.", f.nifty.getScreen("cinematic").findNiftyControl("speechText", Label.class).getText());
        }
        c.toggleObjective();assertTrue(f.panel().isVisible());assertEquals("Convert the target.", f.text("scriptedObjective"));
        c.update(.13f);assertEquals(0, c.captureHudState().updateRemainder());
        f.tabs().setSelectedTabIndex(0);assertEquals(1, selections[0]);
    }
    @Test void missingTabObjectiveAndInvalidMenuFailBeforeChangingLivePanels() throws Exception {
        var f = new Fixture();var saved = f.controller.captureHudState();
        for (var invalid : List.of(copy(saved, 255, null, saved.menu()), copy(saved, 0, 999, saved.menu()),
                copy(saved, 0, null, new HudPanelState.Menu(true, HudPanelState.Page.CONFIRMATION, HudPanelState.Page.QUIT, "Done", HudPanelState.Destination.DEBRIEFING)))) {
            assertThrows(IllegalArgumentException.class, () -> f.controller.restoreHudState(invalid));
            assertEquals(saved, f.controller.captureHudState());
        }
        assertThrows(IllegalArgumentException.class, () -> f.controller.pauseMenuNavigate("CONFIRMATION", "QUIT", "Bad", "arbitraryCallback()"));
        assertEquals(saved, f.controller.captureHudState());
    }
    @Test void hiddenQuitPageAndPendingObjectiveOrderRemainDataUntilOrdinaryBinding() throws Exception {
        var f = new Fixture();f.controller.pauseMenuNavigate("QUIT", "MAIN", null, null);
        var base = f.controller.captureHudState();var pending = new HudPanelState(.2f, null, 8, List.of(9, 123, 8), true, false, 0, base.menu());
        f.controller.restoreHudState(pending);assertEquals(pending, f.controller.captureHudState());
        assertFalse(pending.menu().visible());assertEquals(HudPanelState.Page.QUIT, pending.menu().page());
        assertEquals("Convert the target.", f.text("scriptedObjective"));
    }
    @Test void detachedOrOffRenderOwnerRejectsCaptureAndRestore() throws Exception {
        var f = new Fixture();var saved = f.controller.captureHudState();
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, () -> f.controller.captureHudState());
                assertThrows(IllegalStateException.class, () -> f.controller.restoreHudState(saved));
            }).get(5, TimeUnit.SECONDS);
        }
        assertEquals(saved, f.controller.captureHudState());f.controller.cleanup();
        assertThrows(IllegalStateException.class, () -> f.controller.captureHudState());
    }
    @Test void completedPlayerStillOffersAndRestoresDebriefingAfterEndListenerIsGone() throws Exception {
        var f=new Fixture();
        PlayerState player=(PlayerState)ObjectivePanelTest.field("state").get(f.controller);
        var result=PlayerState.class.getDeclaredField("gameResult");result.setAccessible(true);
        result.set(player,toniarts.openkeeper.game.data.GameResult.completed(true,5));
        assertNull(player.stateManager.getState(EndGameState.class));
        f.controller.pauseMenuNavigate("QUIT","MAIN",null,null);f.nifty.update();
        var column=f.nifty.getScreen("hud").findElementById("optionsMenu").findElementById("#columnOne");
        assertTrue(column.getChildren().stream().anyMatch(element->element.getElementType().toString().contains("quitToDebriefing")));
        var base=f.controller.captureHudState();
        var saved=copy(base,base.selectedTab(),base.objectiveId(),new HudPanelState.Menu(true,
                HudPanelState.Page.CONFIRMATION,HudPanelState.Page.QUIT,"Debrief",HudPanelState.Destination.DEBRIEFING));
        f.controller.restoreHudState(saved);assertEquals(saved,f.controller.captureHudState());
        result.set(player,null);
        assertThrows(IllegalArgumentException.class,()->f.controller.restoreHudState(saved));
    }

    static HudPanelState copy(HudPanelState s, int tab, Integer objective, HudPanelState.Menu menu) {
        return new HudPanelState(s.updateRemainder(), s.cinematicText(), objective, s.pendingObjectives(), s.objectiveVisible(), s.bottomCollapsed(), tab, menu);
    }
    static class Fixture extends ObjectivePanelTest.Fixture {
        Fixture() throws Exception {
            super();nifty.addResourceBundle("menu", new ListResourceBundle() {
                protected Object[][] getContents() {
                    return java.util.stream.IntStream.range(0, 3000).mapToObj(i -> new Object[]{Integer.toString(i), "Menu " + i}).toArray(Object[][]::new);
                }
            });nifty.loadControlFile("Interface/MyControls.xml");nifty.loadStyleFile("Interface/CustomTabStyle.xml");
            nifty.loadControlFile("Interface/CustomTabControl.xml");
            var root = nifty.getScreen("hud").findElementById("layer");
            new PanelBuilder("bottomPanel") {{childLayoutVertical();height("150px");}}.build(root);
            new PanelBuilder("optionsMenu") {{
                childLayoutVertical();visible(false);
                control(new LabelBuilder("#title", ""));
                for (var id : List.of("#columnOne", "#columnTwo", "#navigationColumnOne", "#navigationColumnTwo"))
                    panel(new PanelBuilder(id) {{childLayoutVertical();}});
            }}.build(root);
            new ControlBuilder("tabs-hud", "customTabGroup") {{
                for (int i = 0; i < 2; i++) control(new ControlBuilder("tab-" + i, "customTab") {{
                    parameter("image", "normal.png");parameter("active", "selected.png");parameter("hintText", "Tab");parameter("tooltip", "Tab tip");parameter("sound", "GUI_BUTTON_TAB_CREATURE");width("100%");height("40px");
                }});
            }}.build(root);
            new ScreenBuilder("cinematic") {{layer(new LayerBuilder("caption-layer") {{childLayoutVertical();control(new LabelBuilder("speechText", ""));}});}}.build(nifty);
            nifty.gotoScreen("cinematic");nifty.update();nifty.gotoScreen("hud");nifty.update();tabs().restoreSelectedTabIndex(0);
            ObjectivePanelTest.field("hudThread").set(controller, Thread.currentThread());
            ObjectivePanelTest.field("state").set(controller, new PlayerResultTest.Fixture().player);
        }
        CustomTabGroupControl tabs() {return nifty.getScreen("hud").findControl("tabs-hud", CustomTabGroupControl.class);}
    }
}
