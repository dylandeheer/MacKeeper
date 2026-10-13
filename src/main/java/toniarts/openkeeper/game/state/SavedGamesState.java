/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import de.lessvoid.nifty.Nifty;
import de.lessvoid.nifty.builder.*;
import de.lessvoid.nifty.controls.*;
import de.lessvoid.nifty.controls.button.builder.ButtonBuilder;
import de.lessvoid.nifty.controls.listbox.builder.ListBoxBuilder;
import de.lessvoid.nifty.controls.textfield.builder.TextFieldBuilder;
import de.lessvoid.nifty.elements.Element;
import de.lessvoid.nifty.elements.render.TextRenderer;
import de.lessvoid.nifty.input.NiftyInputEvent;
import de.lessvoid.nifty.input.NiftyStandardInputEvent;
import de.lessvoid.nifty.input.keyboard.KeyboardInputEvent;
import de.lessvoid.nifty.screen.KeyInputHandler;
import de.lessvoid.nifty.screen.Screen;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.utils.AppPaths;

/** Shared save browser over the existing HUD/menu; the transient popup is never part of a saved route. */
public final class SavedGamesState extends AbstractAppState implements Controller {
    private static final String POPUP = "mackeeperSavedGames";
    private final Main app;
    private final boolean saveMode;
    private final String initialMessage;
    private final SaveFiles files = new SaveFiles(AppPaths.dataDirectory());
    private AppStateManager states;
    private Nifty nifty;
    private Screen screen;
    private Element popup;
    private GameClientState client;
    private boolean closed, busy, listing, recovery, resumeOnClose, loading;
    private long generation;
    private String overwriteName;
    private TextField name;
    private ListBox<SaveFiles.Entry> list;
    private enum EscapePress implements NiftyInputEvent { INSTANCE }
    private final KeyInputHandler escapeHandler = event -> {
        if(event==EscapePress.INSTANCE)return true;
        if(event==NiftyStandardInputEvent.Escape){close();return true;}
        return false;
    };

    public static void open(Main app, boolean saveMode) {open(app, saveMode, null);}
    private static void open(Main app, boolean saveMode, String message) {
        app.enqueue(() -> {
            if (app.getStateManager().getState(SavedGamesState.class) == null
                    && app.getStateManager().getState(LocalSessionLoader.class) == null)
                app.getStateManager().attach(new SavedGamesState(app, saveMode, message));
        });
    }
    private SavedGamesState(Main app, boolean saveMode, String message) {
        this.app=app;this.saveMode=saveMode;initialMessage=message;
    }
    @Override public void initialize(AppStateManager manager, Application ignored) {
        super.initialize(manager,app);states=manager;nifty=app.getNifty();screen=nifty.getCurrentScreen();
        client=states.getState(GameClientState.class);
        if (screen==null || client!=null && (!(client.getGameClientService() instanceof LocalGameSession) || !client.isReadyForReplacement())) {
            closed=true;states.detach(this);return;
        }
        if(client!=null) {
            var player=states.getState(PlayerState.class);
            resumeOnClose=player.isEnabled()&&!player.isPaused();
            if(resumeOnClose)player.setPaused(true);
            client.setModalInputBlocked(true);
        }
        try {
            buildPopup();
            screen.addPreKeyboardInputHandler(event -> event.getKey()!=KeyboardInputEvent.KEY_ESCAPE ? null
                    : event.isKeyDown() ? EscapePress.INSTANCE : NiftyStandardInputEvent.Escape, escapeHandler);
            refresh(initialMessage);
        } catch (RuntimeException failure) {
            closeNow(true);
            throw failure;
        }
    }

    private static TextBuilder text(String id,String value,String height,String font) {
        var text=new TextBuilder(id);text.text(value);text.height(height);text.width("100%");
        text.font("Interface/Fonts/"+font+".fnt");text.color("#e3e5e8");text.wrap(true);return text;
    }
    private static ButtonBuilder button(String id,String label,String action) {
        var button=new ButtonBuilder(id,label);button.width("24%");button.height("36px");button.interactOnClick(action);return button;
    }
    private void buildPopup() {
        var builder=new PopupBuilder(POPUP);builder.controller(this);builder.childLayoutCenter();builder.backgroundColor("#000b");
        var panel=new PanelBuilder("savePanel");panel.childLayoutVertical();panel.width("82%");panel.height("82%");panel.padding("20px");panel.backgroundColor("#17202bf2");
        panel.text(text("saveTitle",saveMode?"Save game":"Load game","44px","Frontend28"));
        panel.text(text("saveHint","Choose a saved game, or browse recovery checkpoints.","34px","Frontend14"));
        var tabs=new PanelBuilder();tabs.childLayoutHorizontal();tabs.height("44px");tabs.width("100%");
        var toggle=button("saveRecovery","Show recovery saves","toggleRecovery()");toggle.width("48%");tabs.control(toggle);panel.panel(tabs);
        var entries=new ListBoxBuilder("saveList");entries.width("100%");entries.height("*");entries.displayItems(7);entries.selectionModeSingle();entries.optionalVerticalScrollbar();entries.hideHorizontalScrollbar();panel.control(entries);
        panel.text(text("saveNameLabel","Save name","28px","Frontend14"));
        var field=new TextFieldBuilder("saveName");field.width("100%");field.height("36px");panel.control(field);
        panel.text(text("saveStatus","Reading saved games…","70px","Frontend14"));
        var actions=new PanelBuilder();actions.childLayoutHorizontal();actions.width("100%");actions.height("40px");
        actions.control(button("saveWrite","Save","save()"));actions.control(button("saveLoad","Load selected","load()"));
        actions.control(button("saveRefresh","Refresh","refresh()"));actions.control(button("saveBack","Back","close()"));panel.panel(actions);
        builder.panel(panel);builder.registerPopup(nifty);
        // PopupBuilder retains only the controller class in its registered definition.
        // Supply this owning state explicitly while Nifty instantiates the popup.
        nifty.getControllerFactory().registerFactory(() -> this, SavedGamesState.class);
        try {popup=nifty.createPopup(screen,POPUP);}
        finally {nifty.getControllerFactory().unregisterFactory(SavedGamesState.class);}
        name=popup.findNiftyControl("saveName",TextField.class);list=popup.findNiftyControl("saveList",ListBox.class);
        name.setText("Save "+DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm").format(LocalDateTime.now()));
        if(!saveMode || client==null){name.getElement().hide();popup.findElementById("saveNameLabel").hide();popup.findElementById("saveWrite").hide();}
        nifty.showPopup(screen,popup.getId(),null);buttons();
    }
    @Override public void update(float tpf) {
        if(closed)return;
        if(states.getState(GameClientState.class)!=client || nifty.getCurrentScreen()!=screen){closeNow(false);return;}
        buttons();
    }
    private boolean active(){return !closed&&isInitialized()&&states.getState(SavedGamesState.class)==this;}
    private void buttons() {
        if(popup==null)return;
        var player=states.getState(PlayerState.class);
        popup.findNiftyControl("saveWrite",Button.class).setEnabled(!busy&&!recovery&&client!=null&&player!=null&&player.isPaused());
        popup.findNiftyControl("saveLoad",Button.class).setEnabled(!busy&&!list.getSelection().isEmpty());
        popup.findNiftyControl("saveRecovery",Button.class).setEnabled(!busy);
        popup.findNiftyControl("saveRefresh",Button.class).setEnabled(!busy);
        popup.findNiftyControl("saveBack",Button.class).setEnabled(!busy||listing);
        name.setEnabled(!busy&&!recovery);list.setEnabled(!busy);
        if(overwriteName!=null&&!overwriteName.equals(name.getRealText().strip())) {
            overwriteName=null;popup.findNiftyControl("saveWrite",Button.class).setText("Save");
        }
    }
    private void status(String value){if(popup!=null)popup.findElementById("saveStatus").getRenderer(TextRenderer.class).setText(value);}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T> void work(Work<T> work,Consumer<T> done) {
        busy=true;long request=++generation;buttons();
        Thread.ofVirtual().name("MacKeeper save files").start(()->{
            T result=null;Exception failure=null;try{result=work.run();}catch(Exception error){failure=error;}
            T value=result;Exception error=failure;
            app.enqueue(()->{
                if(!active()||request!=generation)return;
                busy=false;listing=false;
                if(error!=null)status("Could not complete this action. "+message(error));else done.accept(value);
                buttons();
            });
        });
    }
    public void refresh(){if(!busy)refresh(null);}
    private void refresh(String message) {
        listing=true;status("Reading saved games…");boolean recoveries=recovery;
        work(()->files.list(recoveries),entries->{
            list.clear();list.addAllItems(entries);if(!entries.isEmpty())list.selectItemByIndex(0);
            status(message!=null?message:entries.isEmpty()?(recoveries?"No recovery saves yet. A checkpoint is created before replacing an active game.":"No saved games yet. Save from the pause menu during a local game.")
                    :recoveries?"Recovery checkpoints preserve the game that was open before a load.":"Select a save to load. Your current game gets a recovery checkpoint first.");
        });
    }
    public void toggleRecovery() {
        if(busy)return;recovery=!recovery;overwriteName=null;
        popup.findNiftyControl("saveRecovery",Button.class).setText(recovery?"Show my saves":"Show recovery saves");
        popup.findNiftyControl("saveWrite",Button.class).setText("Save");refresh(null);
    }
    public void save() {
        if(busy||recovery||!saveMode||client==null)return;
        var player=states.getState(PlayerState.class);if(player==null||!player.isPaused())return;
        final String proposed;
        try{proposed=SaveFiles.name(name.getRealText());name.setText(proposed);}catch(IllegalArgumentException error){status(error.getMessage());return;}
        boolean confirmed=proposed.equals(overwriteName);
        work(()->Files.exists(files.target(proposed)),exists->{
            if(exists&&!confirmed){overwriteName=proposed;popup.findNiftyControl("saveWrite",Button.class).setText("Replace save");status("A save named “"+proposed+"” exists. Click Replace save to overwrite it, or enter a different name.");return;}
            try {
                var server=states.getState(GameServerState.class);var archive=server.captureLocalSession(client);
                status("Saving “"+proposed+"”…");
                work(()->{files.write(proposed,archive,exists&&confirmed);return proposed;},saved->{
                    overwriteName=null;popup.findNiftyControl("saveWrite",Button.class).setText("Save");refresh("Saved “"+saved+"”.");
                });
            } catch(RuntimeException error){status("Could not save this game. "+message(error));}
        });
    }
    public void load() {
        if(busy||list.getSelection().isEmpty())return;
        Path path=list.getSelection().getFirst().path();busy=true;loading=true;status("Preparing saved game…");buttons();
        LocalSessionLoader.load(app,path).whenComplete((result,error)->{
            var owner=app.getStateManager().getState(GameClientState.class);
            app.enqueue(()->{
                if(app.getStateManager().getState(GameClientState.class)!=owner)return;
                String failure=error!=null?"Could not load this save. "+message(error):result.loaded()?null
                        :"Could not load this save. "+result.error()+(result.recovered()?" Your previous game has been restored.":"");
                if(active()) {
                    busy=false;loading=false;
                    if(failure!=null){status(failure);buttons();}else closeNow(false);
                } else if(failure!=null)open(app,false,failure);
            });
        });
    }
    public void close(){if(!busy||listing)closeNow(true);}
    private void closeNow(boolean resume) {
        if(closed)return;closed=true;generation++;
        if(screen!=null)screen.removePreKeyboardInputHandler(escapeHandler);
        if(client!=null&&states.getState(GameClientState.class)==client) {
            client.setModalInputBlocked(false);
            var player=states.getState(PlayerState.class);
            if(resume&&resumeOnClose&&!loading&&player!=null)player.setPaused(false);
        }
        if(popup!=null)nifty.closePopup(popup.getId());states.detach(this);
    }
    @Override public void cleanup(){if(!closed)closeNow(false);super.cleanup();}
    @Override public void bind(Nifty nifty,Screen screen,Element element,Parameters parameters){}
    @Override public void init(Parameters parameters){}
    @Override public void onStartScreen(){}
    @Override public void onFocus(boolean focus){}
    @Override public void onEndScreen(){if(isInitialized())closeNow(false);}
    @Override public boolean inputEvent(NiftyInputEvent event){if(event==NiftyStandardInputEvent.Escape){close();return true;}return false;}
    private static String message(Throwable error){while(error instanceof CompletionException&&error.getCause()!=null)error=error.getCause();return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
}
