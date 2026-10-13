/*
 * Copyright (C) 2014-2016 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.state;

import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.math.Vector3f;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import com.simsilica.es.*;
import de.lessvoid.nifty.Nifty;
import de.lessvoid.nifty.NiftyEventSubscriber;
import de.lessvoid.nifty.NiftyIdCreator;
import de.lessvoid.nifty.builder.ControlBuilder;
import de.lessvoid.nifty.builder.ControlDefinitionBuilder;
import de.lessvoid.nifty.builder.EffectBuilder;
import de.lessvoid.nifty.builder.ImageBuilder;
import de.lessvoid.nifty.controls.Console;
import de.lessvoid.nifty.controls.Label;
import de.lessvoid.nifty.controls.Tab;
import de.lessvoid.nifty.controls.TabSelectedEvent;
import de.lessvoid.nifty.controls.label.builder.LabelBuilder;
import de.lessvoid.nifty.effects.EffectEventId;
import de.lessvoid.nifty.elements.Element;
import de.lessvoid.nifty.elements.render.ImageRenderer;
import de.lessvoid.nifty.elements.render.PanelRenderer;
import de.lessvoid.nifty.render.NiftyImage;
import de.lessvoid.nifty.render.image.ImageModeFactory;
import de.lessvoid.nifty.render.image.ImageModeHelper;
import de.lessvoid.nifty.screen.Screen;
import de.lessvoid.nifty.spi.sound.SoundHandle;
import de.lessvoid.nifty.tools.Color;
import de.lessvoid.nifty.tools.SizeValue;
import de.lessvoid.nifty.tools.SizeValueType;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.data.ResearchableEntity;
import toniarts.openkeeper.game.sound.GlobalCategory;
import toniarts.openkeeper.game.sound.GlobalType;
import toniarts.openkeeper.gui.nifty.AbstractCreatureCardControl.CreatureUIState;
import toniarts.openkeeper.gui.nifty.*;
import toniarts.openkeeper.gui.nifty.flowlayout.FlowLayoutControl;
import toniarts.openkeeper.gui.nifty.guiicon.GuiIconBuilder;
import toniarts.openkeeper.gui.nifty.icontext.IconTextBuilder;
import toniarts.openkeeper.gui.nifty.jme.ResearchEffectControl;
import toniarts.openkeeper.tools.convert.AssetsConverter;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.tools.convert.map.CreatureSpell;
import toniarts.openkeeper.utils.AssetUtils;
import toniarts.openkeeper.utils.Utils;
import toniarts.openkeeper.view.HudPanelState;
import toniarts.openkeeper.view.creature.CreatureCardRoster;
import toniarts.openkeeper.view.creature.CreatureCardsState;
import toniarts.openkeeper.view.PlayerInteractionState;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState.Type;
import toniarts.openkeeper.view.PossessionInteractionState;
import toniarts.openkeeper.view.text.DoorIconTextParser;
import toniarts.openkeeper.view.text.RoomIconTextParser;
import toniarts.openkeeper.view.text.SpellIconTextParser;
import toniarts.openkeeper.view.text.TrapIconTextParser;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.*;
import java.util.List;

/**
 *
 * @author ArchDemon
 */
public final class PlayerScreenController implements IPlayerScreenController {

    private enum PauseMenuState {

        MAIN, QUIT, CONFIRMATION;
    }
    
    private static final Logger logger = System.getLogger(PlayerScreenController.class.getName());

    public static final float SCREEN_UPDATE_INTERVAL = 0.250f;
    private static final java.awt.Color RESEARCH_COLOR = new java.awt.Color(0.569f, 0.106f, 0.31f, 0.6f);
    
    public float lastUpdate = 0;

    private PlayerState state;
    private Nifty nifty;
    private Screen screen;

    private Element selectedButton;
    private Label tooltip;
    private boolean initHud = false;
    private String cinematicText;
    private toniarts.openkeeper.game.state.session.PlayerScreenState.Route requestedRoute =
            toniarts.openkeeper.game.state.session.PlayerScreenState.Route.EMPTY;
    private String routeInFlight;
    private Thread hudThread;
    private HudPanelState.Menu menuState = HudPanelState.Menu.main(false);
    private GameLevel objectiveLevel;
    private Integer displayedObjectiveId;
    private final Deque<Integer> pendingObjectives = new ArrayDeque<>();
    private EntityData entityData;
    private CreatureCardManager creatureCardManager;
    private PossessionInteractionState.Action possessionAction = PossessionInteractionState.Action.MELEE;

    private final TrapIconTextParser trapIconTextParser = new TrapIconTextParser();
    private final DoorIconTextParser doorIconTextParser = new DoorIconTextParser();
    private final SpellIconTextParser spellIconTextParser = new SpellIconTextParser();
    private final RoomIconTextParser roomIconTextParser = new RoomIconTextParser();

    public PlayerScreenController(PlayerState state, Nifty nifty) {
        this.state = state;
        this.nifty = nifty;
    }

    @Override
    public void update(float tpf) {
        lastUpdate += tpf;

        if (lastUpdate >= SCREEN_UPDATE_INTERVAL) {
            if (creatureCardManager != null) {
                creatureCardManager.update(tpf);
            }
            lastUpdate = 0;
        }
    }

    @Override
    public void cleanup() {
        if (creatureCardManager != null) {
            creatureCardManager.cleanup();
            creatureCardManager = null;
        }

        // Remove the resource bundle so that we can actually put a new one here
        nifty.getResourceBundles().remove("level");

        // The screen controller might sometimes stay in memory
        // So make sure we clean these up or we end up cumulating a lot of uncollectable garbage (memory-leak)
        state = null;
        objectiveLevel = null;
        hudThread = null;
        initHud = false;
        entityData = null;
        screen = null;
    }

    /**
     * Re-establish the player state reference after it has been cleaned up, e.g. when the game is
     * resumed from the end game screen
     *
     * @param state the player state
     */
    void setState(PlayerState state) {
        this.state = state;
    }

    @Override
    public void select(String iState, String id) {
        Type type = Type.valueOf(iState.toUpperCase());
        if (type == Type.SELL) {
            this.playButtonSound(GlobalCategory.GUI_SELL);
        }
        state.interactionState.setInteractionState(type, Integer.parseInt(id));
    }

    public void toggleGui() {
        goToScreen(requestedRoute == toniarts.openkeeper.game.state.session.PlayerScreenState.Route.EMPTY
                ? SCREEN_HUD_ID : SCREEN_EMPTY_ID);
    }

    @Override
    public void togglePanel() {
        // FIXME work but not properly. Map should not move with other things. Need HUD redesign
        Element element = nifty.getScreen(SCREEN_HUD_ID).findElementById("bottomPanel");

        if (!element.getUserDataKeys().contains("toggle")) {
            element.setUserData("toggle", false);
        }

        boolean toggled = element.getUserData("toggle");
        if (toggled) {
            element.setMarginTop(new SizeValue(0, SizeValueType.Pixel));
        } else {
            element.setMarginTop(new SizeValue(156, SizeValueType.Pixel));
        }
        element.setUserData("toggle", !toggled);
        element.getParent().layoutElements();
    }

    @Override
    public void toggleObjective() {
        Element element = nifty.getScreen(SCREEN_HUD_ID).findElementById("objective");
        if (element.isVisible()) {
            element.hide();
        } else {
            showObjectivePanel();
        }
    }

    /** Called on the render thread; the latest instruction remains available on reopening. */
    public void showObjective(int objectiveId) {
        if (objectiveId < 0 || objectiveId > 32767) {
            logger.log(Level.WARNING, "Invalid objective text ID {0}", objectiveId);
            return;
        }
        if (objectiveLevel == null) {
            // Preserve the last validated instruction until the dictionary is restored.
            pendingObjectives.addLast(objectiveId);
            return;
        }
        if (getObjectiveText(objectiveId) == null) {
            logger.log(Level.WARNING, "Missing objective text ID {0} in the level dictionary", objectiveId);
            return;
        }
        displayedObjectiveId = objectiveId;
        showObjectivePanel();
    }

    private void restoreObjectives() {
        if (objectiveLevel == null) return;
        while (!pendingObjectives.isEmpty()) showObjective(pendingObjectives.removeFirst());
        if (displayedObjectiveId != null) showObjectivePanel();
    }

    private String getObjectiveText(Integer objectiveId) {
        var dictionary = nifty.getResourceBundles().get("level");
        if (objectiveId == null || dictionary == null) return null;
        try {
            return dictionary.getString(objectiveId.toString(), nifty.getLocale());
        } catch (MissingResourceException ex) {
            return null;
        }
    }

    private void showObjectivePanel() {
        if (objectiveLevel == null) return;
        Element element = nifty.getScreen(SCREEN_HUD_ID).findElementById("objective");
        element.findNiftyControl("mainObjective", Label.class).setText(objectiveLevel.getMainObjective());
        element.findNiftyControl("subObjective1", Label.class).setText(objectiveLevel.getSubObjective1());
        element.findNiftyControl("subObjective2", Label.class).setText(objectiveLevel.getSubObjective2());
        element.findNiftyControl("subObjective3", Label.class).setText(objectiveLevel.getSubObjective3());
        Element instruction = element.findElementById("scriptedObjectivePanel");
        String text = getObjectiveText(displayedObjectiveId);
        instruction.setVisible(text != null);
        element.findNiftyControl("scriptedObjective", Label.class).setText(text == null ? "" : text);
        element.layoutElements();
        element.show();
    }

    public void saveGame() {SavedGamesState.open(state.app, true);}
    public void loadGame() {SavedGamesState.open(state.app, false);}

    @Override
    public void pauseMenu() {

        // Pause or resume state
        state.setPaused(!state.isPaused());
    }

    @Override
    public void onPaused(boolean paused) {

        // Set the menuButton
        Element menuButton = nifty.getScreen(SCREEN_HUD_ID).findElementById("menuButton");
        if (paused) {
            menuButton.startEffect(EffectEventId.onCustom, null, "select");
        } else {
            menuButton.stopEffect(EffectEventId.onCustom);
        }

        nifty.getScreen(SCREEN_HUD_ID).findElementById("optionsMenu").setVisible(paused);
        if (paused) {
            this.playButtonSound("GUI_BUTTON_OPTIONS");
            pauseMenuNavigate(PauseMenuState.MAIN.name(), null, null, null);
        }
    }

    @Override
    public void pauseMenuNavigate(String menu, String backMenu, String confirmationTitle, String confirmMethod) {
        var page = HudPanelState.Page.valueOf(menu);
        var requested = page == HudPanelState.Page.CONFIRMATION
                ? new HudPanelState.Menu(false, page, HudPanelState.Page.valueOf(backMenu), confirmationTitle, HudPanelState.Destination.fromAction(confirmMethod))
                : new HudPanelState.Menu(false, page, null, null, null);
        Element optionsMenu = nifty.getScreen(SCREEN_HUD_ID).findElementById("optionsMenu");
        Label optionsMenuTitle = optionsMenu.findNiftyControl("#title", Label.class);
        Element optionsColumnOne = optionsMenu.findElementById("#columnOne");
        for (Element element : optionsColumnOne.getChildren()) {
            element.markForRemoval();
        }
        Element optionsColumnTwo = optionsMenu.findElementById("#columnTwo");
        for (Element element : optionsColumnTwo.getChildren()) {
            element.markForRemoval();
        }
        Element optionsNavigationColumnOne = optionsMenu.findElementById("#navigationColumnOne");
        for (Element element : optionsNavigationColumnOne.getChildren()) {
            element.markForRemoval();
        }
        Element optionsNavigationColumnTwo = optionsMenu.findElementById("#navigationColumnTwo");
        for (Element element : optionsNavigationColumnTwo.getChildren()) {
            element.markForRemoval();
        }

        menuState = requested.visible(optionsMenu.isVisible());
        // TODO: @ArchDemon: I think we need to do modern (not original) game menu
        List<GameMenu> items = new ArrayList<>();
        switch (PauseMenuState.valueOf(menu)) {
            case MAIN:
                optionsMenuTitle.setText("${menu.94}");

                     items.add(new GameMenu("i-objective", "${menu.537}", "pauseMenu()", optionsColumnOne));
                items.add(new GameMenu("i-game", "${menu.97}", "pauseMenu()", optionsColumnOne));
                var localClient = state.app.getStateManager().getState(GameClientState.class);
                if (localClient != null && localClient.getGameClientService() instanceof toniarts.openkeeper.game.state.session.LocalGameSession) {
                    items.add(new GameMenu("i-load", "${menu.143}", "loadGame()", optionsColumnOne));
                    items.add(new GameMenu("i-save", "${menu.201}", "saveGame()", optionsColumnOne));
                }
                items.add(new GameMenu("i-quit", "${menu.1266}", String.format("pauseMenuNavigate(%s,%s,null,null)",
                        PauseMenuState.QUIT.name(), PauseMenuState.MAIN.name()), optionsColumnTwo));
                items.add(new GameMenu("i-restart", "${menu.1269}", "pauseMenu()", optionsColumnTwo));
                items.add(new GameMenu("i-accept", "${menu.142}", "pauseMenu()", optionsNavigationColumnOne));

                break;

            case QUIT:
                optionsMenuTitle.setText("${menu.1266}");

                if (state.hasGameResult()) {
                    items.add(new GameMenu("i-quit", "${menu.2913}", String.format("pauseMenuNavigate(%s,%s,${menu.2913},quitToDebriefing())",
                            PauseMenuState.CONFIRMATION.name(), PauseMenuState.QUIT.name()), optionsColumnOne));
                } else {
                    items.add(new GameMenu("i-quit", "${menu.12}", String.format("pauseMenuNavigate(%s,%s,${menu.12},quitToMainMenu())",
                            PauseMenuState.CONFIRMATION.name(), PauseMenuState.QUIT.name()), optionsColumnOne));
                }
                items.add(new GameMenu(Utils.isWindows() ? "i-exit_to_windows" : "i-quit", Utils.isWindows() ? "${menu.13}" : "${menu.14}",
                        String.format("pauseMenuNavigate(%s,%s,%s,quitToOS())", PauseMenuState.CONFIRMATION.name(),
                                PauseMenuState.QUIT.name(), (Utils.isWindows() ? "${menu.13}" : "${menu.14}")), optionsColumnOne));
                items.add(new GameMenu("i-accept", "${menu.142}", "pauseMenu()", optionsNavigationColumnOne));
                items.add(new GameMenu("i-back", "${menu.20}", String.format("pauseMenuNavigate(%s,null,null,null)", PauseMenuState.MAIN),
                        optionsNavigationColumnTwo));
                break;

            case CONFIRMATION:
                optionsMenuTitle.setText(confirmationTitle);
                // Column one
                new LabelBuilder("confirmLabel", "${menu.15}") {
                    {
                        style("textNormal");
                    }
                }.build(optionsColumnOne);

                items.add(new GameMenu("i-accept", "${menu.21}", confirmMethod, optionsColumnOne));
                items.add(new GameMenu("i-accept", "${menu.142}", "pauseMenu()", optionsNavigationColumnOne));
                items.add(new GameMenu("i-back", "${menu.20}", String.format("pauseMenuNavigate(%s,null,null,null)", backMenu),
                        optionsNavigationColumnTwo));

                break;
        }

        // build menu items
        // FIXME id="#image" and "#text" already exist
        for (GameMenu item : items) {
            new IconTextBuilder("menu-" + NiftyIdCreator.generate(), String.format("Textures/GUI/Options/%s.png", item.id), item.title, item.action) {
                {
                }
            }.build(item.parent);
        }

        // Fix layout
        NiftyUtils.resetContraints(optionsMenuTitle);
        optionsMenu.layoutElements();
    }

    @Override
    public void grabGold() {
        state.grabGold(100);
    }

    @Override
    public void zoomToDungeon() {
        state.zoomToDungeon();
    }

    @Override
    public String getTooltipText(String bundleId) {
        String result = Utils.getMainTextResourceBundle().getString(bundleId);

        return state.getTooltipText(result);
    }

    @Override
    public void zoomToEntity(EntityId entityId) {
        state.zoomToEntity(entityId, false);
    }

    @Override
    public void zoomToPosition(Vector3f position) {
        state.zoomToPosition(position, false);
    }

    @Override
    public void pickUpEntity(EntityId entityId) {
        state.pickUpEntity(entityId);
    }

    @Override
    public void workersAmount(String uiState) {
        WorkerAmountControl.State controlState = WorkerAmountControl.State.valueOf(uiState.toUpperCase());
        if (controlState != null) {
            Screen s = nifty.getScreen(SCREEN_HUD_ID);
            WorkerAmountControl cAmount = s.findNiftyControl("tab-workers", WorkerAmountControl.class);
            cAmount.setState(controlState);

            WorkerEqualControl cEqual = s.findNiftyControl("tab-workers-equal", WorkerEqualControl.class);
            cEqual.setState(controlState);

            if (creatureCardManager != null) creatureCardManager.setMode(controlState);
        }
    }

    @Override
    public void quitToMainMenu() {
        state.quitToMainMenu();
    }

    @Override
    public void quitToDebriefing() {
        state.quitToDebriefing();
    }

    @Override
    public void quitToOS() {
        state.quitToOS();
    }

    @Override
    public void bind(Nifty nifty, Screen screen) {
        this.nifty = nifty;
        this.screen = screen;

        new ControlDefinitionBuilder(ResearchEffectControl.CONTROL_NAME) {
            {
                controller(ResearchEffectControl.class.getName());
            }
        }.registerControlDefintion(nifty);
    }

    @Override
    public void onStartScreen() {
        setScreen(nifty.getCurrentScreen());

        switch (screen.getScreenId()) {
            case SCREEN_HUD_ID: {

                if (initHud) {

                    // Init the HUD items
                    initHud = false;
                    initHudItems(state.assetManager, entityData);
                }

                break;
            }
            case SCREEN_POSSESSION_ID:
                // what we need? abitities and spells, also melee
                //final Creature creature = gameState.getLevelData().getCreature((short)13);
                final Creature creature = state.getPossessionCreature();

                Element contentPanel = screen.findElementById("creature-icon");
                if (contentPanel != null) {
                    createCreatureIcon(creature.getIcon1Resource().getName()).build(contentPanel);
                }

                contentPanel = screen.findElementById("creature-filter");
                if (contentPanel != null) {
                    if (creature.getFirstPersonFilterResource() != null) {
                        new ImageBuilder() {
                            {
                                filename(creature.getFirstPersonFilterResource().getName());
                            }
                        }.build(contentPanel);
                    } else if (getFilterResourceName(creature.getCreatureId()) != null) {
                        new ImageBuilder() {
                            {
                                filename(getFilterResourceName(creature.getCreatureId()));
                            }
                        }.build(contentPanel);
                    }

                    if (creature.getFirstPersonGammaEffect() != null) {
                        contentPanel.getRenderer(PanelRenderer.class).setBackgroundColor(getGammaEffectColor(creature.getFirstPersonGammaEffect()));
                    }
                }

                contentPanel = screen.findElementById("creature-abilities");
                if (contentPanel != null) {

                    String ability = getAbilityResourceName(creature.getFirstPersonSpecialAbility1());
                    if (ability != null) {
                        createCreatureAbilityIcon(ability, 1).build(contentPanel);
                    }

                    ability = getAbilityResourceName(creature.getFirstPersonSpecialAbility2());
                    if (ability != null) {
                        createCreatureAbilityIcon(ability, 2).build(contentPanel);
                    }
                }

                contentPanel = screen.findElementById("creature-attacks");
                if (contentPanel != null) {
                    for (Element element : contentPanel.getChildren()) {
                        element.markForRemoval();
                    }

                    createCreatureMeleeIcon(creature.getFirstPersonMeleeResource().getName()).build(contentPanel);

                    int index = 1;
                    for (Creature.Spell s : creature.getSpells()) {
                        // TODO: check creature level availiablity
                        if (s.getCreatureSpellId() == 0) {
                            continue;
                        }
                        //CreatureSpell cs = state.stateManager.getState(GameState.class).getLevelData().getCreatureSpellById(s.getCreatureSpellId());
                        //createCreatureSpellIcon(cs, index++).build(nifty, screen, contentPanel);
                    }
                }
                updatePossessionSelectedItem(possessionAction);
                break;

            case SCREEN_CINEMATIC_ID:
                Label text = screen.findNiftyControl("speechText", Label.class);
                text.setText(cinematicText);
                break;
        }
    }

    @Override
    public void onEndScreen() {
    }

    @NiftyEventSubscriber(id = "tabs-hud")
    public void onTabChange(String id, TabSelectedEvent event) {
        updateSelectedItem(state.getInteractionState());
        Tab tab = event.getTab();
        if (tab instanceof CustomTabControl) {
            String sound = ((CustomTabControl) event.getTab()).getSound();
            this.playButtonSound(sound);
        }
    }

    protected void initHud(GameLevel gameLevel, EntityData entityData) {
        hudThread = Thread.currentThread();
        objectiveLevel = gameLevel;
        String resource = gameLevel.getTextTableId() == null ? null : gameLevel.getTextTableId().getLevelDictFile();
        Screen hud = nifty.getScreen(SCREEN_HUD_ID);

        // Load the level dictionary
        if (resource != null) {
            String levelResource = "Interface/Texts/".concat(resource);
            try {
                nifty.addResourceBundle("level", Main.getResourceBundle(levelResource));
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Failed to load the level dictionary!", ex);
            }
        }

        // Load the HUD
        initHud = true;
        this.entityData = entityData;

        goToScreen(PlayerScreenController.SCREEN_HUD_ID);

        restoreObjectives();
        hud.layoutLayers();
    }

    /** Capture panel state after HUD construction, on its owning render thread. */
    public HudPanelState captureHudState() {
        requireHudOwner();
        var hud = nifty.getScreen(SCREEN_HUD_ID);
        var bottom = hud.findElementById("bottomPanel");
        boolean collapsed = bottom.getUserDataKeys().contains("toggle") && Boolean.TRUE.equals(bottom.getUserData("toggle"));
        return new HudPanelState(lastUpdate, cinematicText, displayedObjectiveId, List.copyOf(pendingObjectives),
                hud.findElementById("objective").isVisible(), collapsed,
                hud.findControl("tabs-hud", CustomTabGroupControl.class).getSelectedTabIndex(),
                menuState.visible(hud.findElementById("optionsMenu").isVisible()));
    }

    /** Restore panel values quietly; the session owns screen routing, pause and interaction state. */
    public void restoreHudState(HudPanelState saved) {
        requireHudOwner();Objects.requireNonNull(saved);
        var hud = nifty.getScreen(SCREEN_HUD_ID);
        var tabs = hud.findControl("tabs-hud", CustomTabGroupControl.class);
        if (saved.selectedTab() >= tabs.getTabCount()) throw new IllegalArgumentException("Saved HUD tab is not installed");
        if (saved.objectiveId() != null && getObjectiveText(saved.objectiveId()) == null)
            throw new IllegalArgumentException("Saved objective text is not installed");
        var menu = saved.menu();
        if (menu.destination() == HudPanelState.Destination.DEBRIEFING
                && (state == null || !state.hasGameResult()))
            throw new IllegalArgumentException("Debriefing confirmation requires a completed game");
        lastUpdate = saved.updateRemainder();cinematicText = saved.cinematicText();
        nifty.getScreen(SCREEN_CINEMATIC_ID).findNiftyControl("speechText", Label.class).setText(cinematicText == null ? "" : cinematicText);
        displayedObjectiveId = saved.objectiveId();pendingObjectives.clear();pendingObjectives.addAll(saved.pendingObjectives());
        showObjectivePanel();hud.findElementById("objective").setVisible(saved.objectiveVisible());
        var bottom = hud.findElementById("bottomPanel");bottom.setUserData("toggle", saved.bottomCollapsed());
        bottom.setMarginTop(new SizeValue(saved.bottomCollapsed() ? 156 : 0, SizeValueType.Pixel));bottom.getParent().layoutElements();
        tabs.restoreSelectedTabIndex(saved.selectedTab());
        if (state != null && state.interactionState != null) updateSelectedItem(state.getInteractionState());
        pauseMenuNavigate(menu.page().name(), menu.back() == null ? null : menu.back().name(), menu.title(),
                menu.destination() == null ? null : menu.destination().action());
        hud.findElementById("optionsMenu").setVisible(menu.visible());menuState = menu;
        // Hover text belongs to the current pointer, not the saved world.
        if (tooltip != null) tooltip.setText("");
    }

    public CreatureCardsState captureCreatureCards() {
        requireHudOwner();
        if (creatureCardManager == null) throw new IllegalStateException("Creature cards are not initialized");
        return creatureCardManager.capture();
    }

    public void restoreCreatureCards(CreatureCardsState saved) {
        requireHudOwner();
        if (creatureCardManager == null) throw new IllegalStateException("Creature cards are not initialized");
        creatureCardManager.restore(saved);
        workersAmount(saved.mode().name());
    }

    private void requireHudOwner() {
        if (hudThread != Thread.currentThread() || objectiveLevel == null || initHud)
            throw new IllegalStateException("HUD panels require the initialized render-thread owner");
    }

    public void setPause(boolean paused) {
        nifty.getScreen(SCREEN_HUD_ID).findElementById("optionsMenu").setVisible(paused);
    }

    public Element getGuiConstraint() {
        Element middle = nifty.getScreen(SCREEN_HUD_ID).findElementById("middle");
        return middle;
    }

    public Label getTooltip() {
        if (tooltip == null) {
            tooltip = nifty.getScreen(SCREEN_HUD_ID).findNiftyControl("tooltip", Label.class);
        }

        return tooltip;
    }

    public Console getConsole() {
        return nifty.getScreen(SCREEN_HUD_ID).findNiftyControl("console", Console.class);
    }

    public Element getMessages() {
        return nifty.getScreen(SCREEN_HUD_ID).findElementById("systemMessages");
    }

    /**
     * Sets a level based text as cinematic text (subtitles)
     *
     * @param textId the text id from the dict
     * @param introduction is introduction
     * @param pathId
     */
    public void setCinematicText(int textId, boolean introduction, int pathId) {
        setCinematicText(String.format("${level.%d}", textId - 1));
    }

    /**
     * Sets a general text as cinematic text (subtitles)
     *
     * @param textId id taken from the main bundle
     */
    public void setCinematicText(int textId) {
        setCinematicText(Utils.getMainTextResourceBundle().getString(Integer.toString(textId)));
    }

    private void setCinematicText(String text) {
        Label label = nifty.getScreen(SCREEN_CINEMATIC_ID).findNiftyControl("speechText", Label.class);
        cinematicText = text;
        label.setText(cinematicText);
    }

    private void setScreen(Screen screen) {
        this.screen = screen;
    }

    public void goToScreen(String screen) {
        var route = toniarts.openkeeper.game.state.session.PlayerScreenState.Route.fromScreenId(screen);
        if (nifty.getScreen(screen) == null) throw new IllegalArgumentException("Player screen is not installed");
        requestedRoute = route;
        applyRequestedRoute();
    }

    /** Pump once per render frame, after Nifty has processed the preceding frame's transitions. */
    void updateScreenRouting() {
        Screen current = nifty.getCurrentScreen();
        if (current != null && current.isRunning() && !current.isEffectActive(EffectEventId.onEndScreen)) {
            // A previous gotoScreen can have been silently rejected by another owner's transition.
            // Once Nifty is settled again, retry the desired route even if that attempt never arrived.
            routeInFlight = null;
        }
        applyRequestedRoute();
    }

    private void applyRequestedRoute() {
        if (routeInFlight != null) return;
        Screen current = nifty.getCurrentScreen();
        if (current != null && (!current.isRunning() || current.isEffectActive(EffectEventId.onEndScreen))) return;
        String destination = requestedRoute.screenId();
        if (current != null && destination.equals(current.getScreenId())) {
            if (initHud && destination.equals(SCREEN_HUD_ID)) onStartScreen();
            return;
        }
        routeInFlight = destination;
        nifty.gotoScreen(destination);
    }

    boolean isRouteReady() {
        Screen current = nifty.getCurrentScreen();
        return routeInFlight == null && current != null && current.isRunning()
                && !current.isEffectActive(EffectEventId.onEndScreen)
                && requestedRoute.screenId().equals(current.getScreenId()) && !initHud;
    }

    toniarts.openkeeper.game.state.session.PlayerScreenState.Route getRequestedRoute() { return requestedRoute; }
    String getCinematicText() { return cinematicText; }

    void validateScreenRestore(toniarts.openkeeper.game.state.session.PlayerScreenState saved) {
        if (nifty.getScreen(saved.route().screenId()) == null
                || nifty.getScreen(SCREEN_CINEMATIC_ID) == null)
            throw new IllegalArgumentException("Saved player screen is not installed");
        if (saved.playerEnabled()) requireHudOwner();
        if (saved.route() == toniarts.openkeeper.game.state.session.PlayerScreenState.Route.POSSESSION
                && (state == null || state.getPossessionCreature() == null))
            throw new IllegalArgumentException("Possession target is not prepared");
    }

    void restoreScreenRouting(toniarts.openkeeper.game.state.session.PlayerScreenState saved) {
        cinematicText = saved.cinematicText();
        nifty.getScreen(SCREEN_CINEMATIC_ID).findNiftyControl("speechText", Label.class)
                .setText(cinematicText == null ? "" : cinematicText);
        if (saved.playerEnabled()) {
            Element button = nifty.getScreen(SCREEN_HUD_ID).findElementById("menuButton");
            if (saved.paused()) button.startEffect(EffectEventId.onCustom, null, "select");
            else button.stopEffect(EffectEventId.onCustom);
        }
        requestedRoute = saved.route();applyRequestedRoute();
    }

    /**
     * FIXME not applied when enter possession twice Focus active Action element
     * on GUI
     *
     * @param action
     */
    protected void updatePossessionSelectedItem(PossessionInteractionState.Action action) {
        possessionAction = action;
        Element element = nifty.getScreen(SCREEN_POSSESSION_ID).findElementById("creature-" + possessionAction.toString().toLowerCase());
        if (element != null) {
            element.setFocus();
        }
    }

    /**
     * Initializes the HUD items, such as buildings, spells etc. to level
     * initials
     */
    private void initHudItems(AssetManager assetManager, EntityData entityData) {
        Screen hud = nifty.getScreen(SCREEN_HUD_ID);

        // Stretch the background image (height-wise) on the background image panel
        try {
            BufferedImage img = AssetUtils.readImageFromAsset(assetManager.locateAsset(new AssetKey("Textures/GUI/Windows/Panel-BG.png")));

            // Scale the backgroung image to the panel height, keeping the aspect ratio
            Element panel = nifty.getCurrentScreen().findElementById("bottomBackgroundPanel");
            BufferedImage newImage = new BufferedImage(panel.getHeight() * img.getWidth() / img.getHeight(), panel.getHeight(), img.getType());
            Graphics2D g = newImage.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(img, 0, 0, newImage.getWidth(), newImage.getHeight(), 0, 0, img.getWidth(), img.getHeight(), null);
            g.dispose();

            // Convert the new image to a texture, and add a dummy cached entry to the asset manager
            AWTLoader loader = new AWTLoader();
            Texture tex = new Texture2D(loader.load(newImage, false));
            ((DesktopAssetManager) assetManager).addToCache(new TextureKey("HUDBackground", false), tex);

            // Add the scaled one
            NiftyImage niftyImage = nifty.createImage("HUDBackground", true);
            String resizeString = "repeat:0,0," + newImage.getWidth() + "," + newImage.getHeight();
            String areaProviderProperty = ImageModeHelper.getAreaProviderProperty(resizeString);
            String renderStrategyProperty = ImageModeHelper.getRenderStrategyProperty(resizeString);
            niftyImage.setImageMode(ImageModeFactory.getSharedInstance().createImageMode(areaProviderProperty, renderStrategyProperty));
            ImageRenderer renderer = panel.getRenderer(ImageRenderer.class);
            renderer.setImage(niftyImage);
        } catch (IOException ex) {
            logger.log(Level.ERROR, "Failed to open the background image!", ex);
        }

//        PlayerManaControl manaControl = state.getPlayer().getManaControl();
//        if (manaControl != null) {
        //manaControl.addListener(hud.findNiftyControl("mana", Label.class), PlayerManaControl.Type.CURRENT);
        // manaControl.addListener(hud.findNiftyControl("manaGet", Label.class), PlayerManaControl.Type.GAIN);
        //manaControl.addListener(hud.findNiftyControl("manaLose", Label.class), PlayerManaControl.Type.LOSE);
//        }
        hud.findNiftyControl("mana", Label.class).setText(Integer.toString(state.getPlayer().getMana()));
        hud.findNiftyControl("manaGet", Label.class).setText(Integer.toString(state.getPlayer().getManaGain()));
        hud.findNiftyControl("manaLose", Label.class).setText(Integer.toString(state.getPlayer().getManaLoose()));

        if (state.getGoldControl() != null) {
            // state.getGoldControl().addListener(hud.findNiftyControl("gold", Label.class));
        }
        hud.findNiftyControl("gold", Label.class).setText(Long.toString(state.getPlayer().getGold()));

        // Player creatures
        Element creatureTab = hud.findElementById("tab-creature");
        final Element creaturePanel = creatureTab.findElementById("tab-creature-content");
        removeAllChildElements(creaturePanel);

        // Player creatures, workers, we always recreate this
        final Element workersPanel = creatureTab.findElementById("tab-creature-container");
        Element element = workersPanel.findElementById("tab-workers");
        if (element != null) {
            element.markForRemoval();
        }
        ControlBuilder cb = new ControlBuilder("workerAmount") {
            {
                parameter("creatureId", Integer.toString(state.getKwdFile().getImp().getCreatureId()));
                id("tab-workers");
            }
        };
        element = cb.build(workersPanel, 0);
        WorkerAmountControl workerAmountControl = element.getControl(WorkerAmountControl.class);

        // Player creatures, the controller
        creatureCardManager = new CreatureCardManager(this, state.getKwdFile(), entityData, creaturePanel,
                creatureTab.findControl("tab-creature-scroll", CreaturePortraitScrollControl.class),
                workerAmountControl, hud, state.getPlayer().getId());
        // Rooms
//        state.getRoomControl().addRoomAvailabilityListener(new PlayerRoomControl.IRoomAvailabilityListener() {
//
//            @Override
//            public void onChange() {
//                populateRoomTab();
//            }
//        });
        populateRoomTab();
        // Spells
//        state.getSpellControl().addPlayerSpellListener(new PlayerSpellListener() {
//            @Override
//            public void onAdded(PlayerSpell spell) {
//                populateSpellTab();
//            }
//
//            @Override
//            public void onRemoved(PlayerSpell spell) {
//                populateSpellTab();
//            }
//
//            @Override
//            public void onResearchStatusChanged(PlayerSpell spell) {
//                // FIXME: implement properly
//                populateSpellTab();
//            }
//        });
        populateSpellTab();
        populateManufactureTab();
//        FlowLayoutControl contentPanel = hud.findElementById("tab-workshop-content").getControl(FlowLayoutControl.class);
//        contentPanel.removeAll();
//        for (final Door door : state.getAvailableDoors()) {
//            contentPanel.addElement(createDoorIcon(door));
//        }
//        for (final Trap trap : state.getAvailableTraps()) {
//            contentPanel.addElement(createTrapIcon(trap));
//        }
    }

    public void setGold(long gold) {
        Screen hud = nifty.getScreen(SCREEN_HUD_ID);
        hud.findNiftyControl("gold", Label.class).setText(Long.toString(gold));
    }

    public void setMana(int mana, int manaLoose, int manaGain) {
        Screen hud = nifty.getScreen(SCREEN_HUD_ID);
        hud.findNiftyControl("mana", Label.class).setText(Integer.toString(mana));
        hud.findNiftyControl("manaGet", Label.class).setText(Integer.toString(manaGain));
        hud.findNiftyControl("manaLose", Label.class).setText(Integer.toString(manaLoose));
    }

    public void populateManufactureTab() {
        Screen hud = nifty.getScreen(SCREEN_HUD_ID);
        FlowLayoutControl contentPanel = hud.findElementById("tab-workshop-content").getControl(FlowLayoutControl.class);
        contentPanel.removeAll();
        for (final ResearchableEntity door : state.getAvailableDoors()) {
            Element element = contentPanel.addElement(createDoorIcon(door));
            setResearchEffect(door, element);
        }
        for (final ResearchableEntity trap : state.getAvailableTraps()) {
            Element element = contentPanel.addElement(createTrapIcon(trap));
            setResearchEffect(trap, element);
        }
    }

    /**
     * Sets research effect on top of the element if needed, the basic effect.
     * Not to be used for keeper spells
     *
     * @param researchableEntity the entity the element is for
     * @param element the Nifty element created for the entity
     */
    private void setResearchEffect(final ResearchableEntity researchableEntity, final Element element) {
        if (!researchableEntity.isDiscovered()) {
            ResearchEffectControl researchControl = new ControlBuilder(ResearchEffectControl.CONTROL_NAME) {
                {
                    parameter("color", Integer.toString(RESEARCH_COLOR.getRGB()));
                    parameter("image", "");
                }
            }.build(element).getControl(ResearchEffectControl.class);
            researchControl.initJme(state.app);
        }
    }

    /**
     * Populates the player spells tab
     */
    public void populateSpellTab() {
        Screen hud = nifty.getScreen(SCREEN_HUD_ID);
        FlowLayoutControl contentPanel = hud.findElementById("tab-spell-content").getControl(FlowLayoutControl.class);
        contentPanel.removeAll();
        for (final ResearchableEntity spell : state.getAvailableKeeperSpells()) {
            Element element = contentPanel.addElement(createSpellIcon(spell));
            if (!spell.isUpgraded()) {
                ResearchEffectControl researchControl = new ControlBuilder(ResearchEffectControl.CONTROL_NAME) {
                    {
                        parameter("color", spell.isDiscovered() ? "" : Integer.toString(RESEARCH_COLOR.getRGB()));
                        parameter("image", spell.isDiscovered() ? AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER + File.separator + "GUI/Icons/Gold_Frame.png") : "");
                    }
                }.build(element).getControl(ResearchEffectControl.class);
                researchControl.initJme(state.app);
            }
        }
    }

    /**
     * Populates the player rooms tab
     */
    public void populateRoomTab() {
        Screen hud = nifty.getScreen(SCREEN_HUD_ID);
        FlowLayoutControl contentPanel = hud.findElementById("tab-room-content").getControl(FlowLayoutControl.class);
        contentPanel.removeAll();
        for (final ResearchableEntity room : state.getAvailableRoomsToBuild()) {
            Element element = contentPanel.addElement(createRoomIcon(room));
            setResearchEffect(room, element);
        }
    }

    /**
     * Deletes all children from element
     *
     * @param element parent
     */
    private void removeAllChildElements(Element element) {
        for (Element e : element.getChildren()) {
            e.markForRemoval();
        }
    }

    // FIXME where filter resource?
    private String getFilterResourceName(int id) {
        String resource = null;
        switch (id) {
            case 12:
                resource = "Textures/GUI/Filters/F-FireFly.png";
                break;
            case 13:
                resource = "Textures/GUI/Filters/F-Knight.png";
                break;
            case 22:
                resource = "Textures/GUI/Filters/F-Black_Knight.png";
                break;
            case 25:
                resource = "Textures/GUI/Filters/F-Guard.png";
                break;
        }
        return resource;
    }

    // FIXME Gamma Effect not a Color. It`s post effect of render.
    private Color getGammaEffectColor(Creature.GammaEffect type) {
        Color c = new Color(0, 0, 0, 0);

        switch (type) {
            case NORMAL:
                c = new Color(1f, 1f, 1f, 0);
                break;

            case VAMPIRE_RED:
                c = new Color(0.8f, 0, 0, 0.2f);
                break;

            case DARK_ELF_PURPLE:
                c = new Color(0.9f, 0, 0.9f, 0.2f);
                break;

            case SKELETON_BLACK_N_WHITE:
                c = new Color(0, 0, 1f, 0.1f);
                break;

            case SALAMANDER_INFRARED:
                c = new Color(1f, 0, 0, 0.1f);
                break;

            case DARK_ANGER_BRIGHT_BLUE:
            case DEATH_VIEW_HALF_RED:
            case FREEZE_VIEW_ONLY_BLUE:
            case BLACKOUT:
            case GHOST:
            case MAIDEN:
            case REDOUT:
            case WHITEOUT:
                break;

        }

        return c;
    }

    // FIXME I doesn`t find resources to abilities
    private String getAbilityResourceName(Creature.SpecialAbility ability) {
        String name = null;
        switch (ability) {
            case HYPNOTISE:
                name = "GUI/Icons/1st-person/hypnotise-mode";
                break;
            case TURN_TO_BAT:
                name = "GUI/Icons/1st-person/bat-mode";
                break;
            case PICK_UP_WORKERS:
                // Throw_Imp-Mode
                // throw_book-mode
                name = "GUI/Icons/1st-person/throw-mode";
                break;
            case PRAY:
                name = "GUI/Icons/1st-person/pray-mode";
                break;
            case SNIPER_MODE:
                name = "GUI/Icons/1st-person/sniper-mode";
                break;
            case DISGUISE_N_STEAL:
                name = "GUI/Icons/1st-person/sneak-mode";
                break;
        }
        return name;
    }

    public void updateEntityResearch(ResearchableEntity researchableEntity) {
        String itemId = getItemTypeId(researchableEntity);
        Element researchableButton = nifty.getScreen(SCREEN_HUD_ID).findElementById(itemId);
        if (researchableButton != null) {
            ResearchEffectControl researchControl = researchableButton.getControl(ResearchEffectControl.class);
            if (researchControl != null) {
                float percentage = (float) researchableEntity.getResearch() / getResearchTime(researchableEntity);
                researchControl.setResearch(percentage);
            }
        }
    }

    private int getResearchTime(ResearchableEntity researchableEntity) {
        switch (researchableEntity.getResearchableType()) {
            case SPELL:
                KeeperSpell keeperSpell = state.getKwdFile().getKeeperSpellById(researchableEntity.getId());
                return researchableEntity.isDiscovered() ? keeperSpell.getBonusRTime() : keeperSpell.getResearchTime();
            case TRAP:
                return state.getKwdFile().getTrapById(researchableEntity.getId()).getResearchTime();
            case DOOR:
                return state.getKwdFile().getDoorById(researchableEntity.getId()).getResearchTime();
            case ROOM:
                return state.getKwdFile().getRoomById(researchableEntity.getId()).getResearchTime();
            default:
                throw new IllegalArgumentException("Invalid type!");
        }
    }

    private String getItemTypeId(ResearchableEntity researchableEntity) {
        String prefix = null;
        switch (researchableEntity.getResearchableType()) {
            case SPELL: {
                prefix = "spell_";
                break;
            }
            case TRAP: {
                prefix = "trap_";
                break;
            }
            case DOOR: {
                prefix = "door_";
                break;
            }
            case ROOM: {
                prefix = "room_";
                break;
            }
            default:
                throw new IllegalArgumentException("Invalid type!");
        }

        return prefix + researchableEntity.getId();
    }

    protected void updateSelectedItem(PlayerInteractionState.InteractionState state) {
        if (selectedButton != null) {
            selectedButton.stopEffect(EffectEventId.onCustom);
        }

        String itemId = state.getType().toString().toLowerCase() + "_" + state.getItemId();
        selectedButton = nifty.getScreen(SCREEN_HUD_ID).findElementById(itemId);
        if (selectedButton != null) {
            selectedButton.startEffect(EffectEventId.onCustom, null, "select");
        }
    }

    private ImageBuilder createCreatureAbilityIcon(final String name, final int index) {

        return new ImageBuilder() {
            {
                valignCenter();
                marginRight("6px");
                focusable(true);
                id("creature-ability_" + index);
                filename(AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER
                        + File.separator + name + ".png"));
                valignCenter();
                onFocusEffect(new EffectBuilder("imageOverlay") {
                    {
                        effectParameter("filename", AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER
                                + File.separator + "GUI/Icons/selected-creature.png"));
                        post(true);
                    }
                });
            }
        };
    }

    private ImageBuilder createCreatureIcon(final String name) {
        return new ImageBuilder() {
            {
                filename(AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER
                        + File.separator + name + ".png"));
                valignCenter();
            }
        };
    }

    private ImageBuilder createCreatureMeleeIcon(final String name) {
        return new ImageBuilder() {
            {
                filename(AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER
                        + File.separator + name + ".png"));
                valignCenter();
                marginLeft("6px");
                focusable(true);
                id("creature-melee");
                onFocusEffect(new EffectBuilder("imageOverlay") {
                    {
                        effectParameter("filename", AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER
                                + File.separator + "GUI/Icons/selected-creature.png"));
                        post(true);
                    }
                });
            }
        };
    }

    private ImageBuilder createCreatureSpellIcon(final CreatureSpell cs, final int index) {
        return new ImageBuilder() {
            {
                filename(AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER
                        + File.separator + cs.getGuiIcon().getName() + ".png"));
                valignCenter();
                marginLeft("6px");
                focusable(true);
                id("creature-spell_" + index);
                onFocusEffect(new EffectBuilder("imageOverlay") {
                    {
                        effectParameter("filename", AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER
                                + File.separator + "GUI/Icons/selected-spell.png"));
                        post(true);
                    }
                });
            }
        };
    }

    private ControlBuilder createRoomIcon(final ResearchableEntity roomInfo) {
        Room room = state.getKwdFile().getRoomById(roomInfo.getId());
        if (roomInfo.isDiscovered()) {
            String tip = roomIconTextParser.parseText(Utils.getMainTextResourceBundle().getString(Integer.toString(room.getGeneralDescriptionStringId())), room);
            String hint = roomIconTextParser.parseText(Utils.getMainTextResourceBundle().getString("1783"), room);

            return createIcon(room.getRoomId(), "room", room.getGuiIcon(), tip,
                    hint, true, false);
        }

        return createIcon(room.getId(),
                "room", "gui\\rooms\\tba", null, null, false, false);
    }

    private ControlBuilder createSpellIcon(final ResearchableEntity spell) {
        KeeperSpell keeperSpell = state.getKwdFile().getKeeperSpellById(spell.getId());
        if (spell.isDiscovered()) {
            String tip = spellIconTextParser.parseText(Utils.getMainTextResourceBundle().getString(Integer.toString(keeperSpell.getGeneralDescriptionStringId())), keeperSpell, spell);
            String hint = spellIconTextParser.parseText(Utils.getMainTextResourceBundle().getString("1785"), keeperSpell, spell);

            return createIcon(keeperSpell.getKeeperSpellId(), "spell", spell.isUpgraded() ? keeperSpell.getGuiIcon().getName() + "-2" : keeperSpell.getGuiIcon().getName(), tip, hint, true, spell.isUpgraded());
        }
        return createIcon(keeperSpell.getKeeperSpellId(),
                "spell", "gui\\spells\\s-tba", null, null, false, false);
    }

    private ControlBuilder createDoorIcon(final ResearchableEntity doorInfo) {
        Door door = state.getKwdFile().getDoorById(doorInfo.getId());
        if (doorInfo.isDiscovered()) {
            Trap trap = state.getKwdFile().getTrapById(door.getTrapTypeId());
            String tip = doorIconTextParser.parseText(Utils.getMainTextResourceBundle().getString(Integer.toString(door.getGeneralDescriptionStringId())), door, trap);
            String hint = doorIconTextParser.parseText(Utils.getMainTextResourceBundle().getString("1783"), door, trap);

            return createIcon(door.getDoorId(), "door", door.getGuiIcon(), tip, hint, true, false);
        }

        return createIcon(door.getId(),
                "door", "gui\\traps\\w-tba", null, null, false, false);
    }

    private ControlBuilder createTrapIcon(final ResearchableEntity trapInfo) {
        Trap trap = state.getKwdFile().getTrapById(trapInfo.getId());
        if (trapInfo.isDiscovered()) {
            String tip = trapIconTextParser.parseText(Utils.getMainTextResourceBundle().getString(Integer.toString(trap.getGeneralDescriptionStringId())), trap);
            String hint = trapIconTextParser.parseText(Utils.getMainTextResourceBundle().getString("1783"), trap);

            return createIcon(trap.getTrapId(), "trap", trap.getGuiIcon(), tip, hint, true, false);
        }

        return createIcon(trap.getId(),
                "trap", "gui\\traps\\w-tba", null, null, false, false);
    }

    public ControlBuilder createIcon(final int id, final String type, final ArtResource guiIcon, final String tooltip, final String hint, final boolean allowSelect, final boolean hilightGold) {
        return createIcon(id, type, guiIcon.getName(), tooltip, hint, allowSelect, hilightGold);
    }

    public ControlBuilder createIcon(final int id, final String type, final String guiIcon, final String tooltip, final String hint, final boolean allowSelect, final boolean hilightGold) {
        ControlBuilder cb = new GuiIconBuilder(type + "_" + id,
                AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER + File.separator + guiIcon + ".png"),
                AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER + File.separator + (hilightGold ? "GUI/Icons/Hilight-2.png" : "GUI/Icons/hilight.png")),
                AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER + File.separator + "GUI/Icons/selected-" + type + ".png"),
                hint != null ? hint : "",
                tooltip != null ? tooltip : "",
                "select(" + type + ", " + id + ")");
        if (!allowSelect) {
            cb.parameter("enabled", Boolean.toString(false));
        }
        return cb;
    }

    @Override
    public void playSound(String category, String id) {
        SoundHandle soundHandler = NiftyUtils.getSoundHandler(nifty, category, Integer.parseInt(id));
        if (soundHandler != null) {
            soundHandler.play();
        }
    }

    @Override
    public void playButtonSound(String category) {
        this.playSound(category, String.valueOf(GlobalType.GUI_BUTTON_CKICK.getId()));
    }

    /**
     * Simple class to handle the population, methods and updates of the
     * creature cards and worker amounts
     */
    private static final class CreatureCardManager {
        private final IPlayerScreenController playerScreenController;
        private final IKwdFile kwdFile;
        private final Element creaturePanel;
        private final CreaturePortraitScrollControl portraitScroll;
        private final WorkerAmountControl workerAmountControl;
        private final Screen hud;
        private final CreatureCardRoster roster;
        private final Map<Short, CreatureCardControl> creatureCardControls = new LinkedHashMap<>();
        private final CreatureCardEventListener creatureCardEventListener = new CreatureCardEventListener() {
            @Override public void zoomTo(short creatureId, CreatureUIState filter) {
                var value = roster.selectNext(creatureId, filter);
                if (value != null) playerScreenController.zoomToPosition(value.position().vector());
            }
            @Override public void pickUp(short creatureId, CreatureUIState filter) {
                var value = roster.selectNext(creatureId, filter);
                if (value != null) playerScreenController.pickUpEntity(value.id());
            }
        };

        public CreatureCardManager(IPlayerScreenController controller, IKwdFile definition, EntityData data,
                Element panel, CreaturePortraitScrollControl scroll, WorkerAmountControl workers, Screen hud, short playerId) {
            playerScreenController = controller;kwdFile = definition;creaturePanel = panel;portraitScroll = scroll;
            workerAmountControl = workers;this.hud = hud;
            roster = new CreatureCardRoster(data, playerId, definition.getImp().getCreatureId());
            workerAmountControl.addListener(creatureCardEventListener);
            portraitScroll.setPortraits(List.of());refreshCounts();
        }

        public void update(float tpf) {
            roster.update();var order = roster.cards();
            if (!order.equals(new ArrayList<>(creatureCardControls.keySet()))) publishCards(prepareCards(order));
            refreshCounts();
        }

        public void cleanup() {
            roster.close();workerAmountControl.removeListener(creatureCardEventListener);
            for (var card : creatureCardControls.values()) card.removeListener(creatureCardEventListener);
        }

        CreatureCardsState capture() {
            return roster.capture(workerAmountControl.getState(), portraitScroll.getFirstVisible());
        }

        void restore(CreatureCardsState saved) {
            roster.validate(saved, kwdFile);
            // Construct missing controls before replacing behavioral state or removing live cards.
            var prepared = prepareCards(saved.cards());
            try {roster.restore(saved, kwdFile);}
            catch (RuntimeException failure) {
                for (var entry : prepared.entrySet()) if (!creatureCardControls.containsKey(entry.getKey())) removeCard(entry.getValue());
                throw failure;
            }
            publishCards(prepared);refreshCounts();
            portraitScroll.restoreFirstVisible(saved.firstVisible());
        }

        void setMode(WorkerAmountControl.State mode) {
            for (var card : creatureCardControls.values()) card.setState(mode);
        }

        private Map<Short, CreatureCardControl> prepareCards(List<Short> order) {
            var prepared = new LinkedHashMap<Short, CreatureCardControl>();
            var created = new ArrayList<CreatureCardControl>();
            try {
                for (short type : order) {
                    var card = creatureCardControls.get(type);
                    if (card == null) {
                        card = createPlayerCreatureIcon(kwdFile.getCreature(type), hud, creaturePanel);
                        created.add(card);card.getElement().hide();card.setState(workerAmountControl.getState());
                    }
                    prepared.put(type, card);
                }
                return prepared;
            } catch (RuntimeException failure) {
                for (var card : created) removeCard(card);
                throw failure;
            }
        }

        private void publishCards(Map<Short, CreatureCardControl> prepared) {
            for (var entry : creatureCardControls.entrySet()) if (!prepared.containsKey(entry.getKey())) removeCard(entry.getValue());
            creatureCardControls.clear();creatureCardControls.putAll(prepared);int index = 0;
            for (var card : creatureCardControls.values()) {card.getElement().setIndex(index++);card.getElement().show();}
            portraitScroll.setPortraits(creatureCardControls.values().stream().map(CreatureCardControl::getElement).toList());
        }

        private void removeCard(CreatureCardControl card) {
            card.removeListener(creatureCardEventListener);card.getElement().hide();card.getElement().markForRemoval();
        }

        private void refreshCounts() {
            short worker = roster.workerId();
            workerAmountControl.setTotal(roster.count(worker, null));
            workerAmountControl.setIdle(roster.count(worker, CreatureUIState.IDLE));
            workerAmountControl.setBusy(roster.count(worker, CreatureUIState.BUSY));
            workerAmountControl.setFight(roster.count(worker, CreatureUIState.FIGHT));
            for (var entry : creatureCardControls.entrySet()) {
                short type = entry.getKey();var card = entry.getValue();
                card.setTotal(roster.count(type, null));card.setIdle(roster.count(type, CreatureUIState.IDLE));
                card.setBusy(roster.count(type, CreatureUIState.BUSY));card.setFight(roster.count(type, CreatureUIState.FIGHT));
            }
        }

        private CreatureCardControl createPlayerCreatureIcon(Creature creature, Screen hud, Element parent) {
            ControlBuilder cb = new ControlBuilder("creature") {
                {
                    filename(AssetUtils.getCanonicalAssetKey(AssetsConverter.TEXTURES_FOLDER
                            + File.separator + creature.getPortraitResource().getName() + ".png"));
                    parameter("creatureId", Integer.toString(creature.getCreatureId()));
                    parameter("creatureName", Utils.getMainTextResourceBundle().getString(
                            Integer.toString(creature.getNameStringId())));
                    id("creature_" + creature.getCreatureId() + "_" + NiftyIdCreator.generate());
                }
            };
            Element element = cb.build(parent);
            CreatureCardControl creatureCardControl = element.getControl(CreatureCardControl.class);

            // Add the event listener
            creatureCardControl.addListener(creatureCardEventListener);

            return creatureCardControl;
        }

    }

    private static final class GameMenu {

        protected String title;
        protected String action;
        protected String id;
        protected Element parent;

        public GameMenu() {
        }

        public GameMenu(String id, String title, String action, Element parent) {
            this.id = id;
            this.title = title;
            this.action = action;
            this.parent = parent;
        }
    }
}
