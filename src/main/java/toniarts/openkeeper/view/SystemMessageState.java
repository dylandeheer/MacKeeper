/*
 * Copyright (C) 2014-2015 OpenKeeper
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
package toniarts.openkeeper.view;

import com.jme3.app.Application;
import com.jme3.app.state.AppStateManager;
import de.lessvoid.nifty.NiftyIdCreator;
import de.lessvoid.nifty.builder.ControlBuilder;
import de.lessvoid.nifty.elements.Element;
import de.lessvoid.nifty.spi.sound.SoundHandle;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.sound.GlobalCategory;
import toniarts.openkeeper.game.sound.GlobalType;
import toniarts.openkeeper.game.state.AbstractPauseAwareState;
import toniarts.openkeeper.gui.nifty.NiftyUtils;
import toniarts.openkeeper.gui.nifty.message.SystemMessageControl;
import toniarts.openkeeper.utils.AssetUtils;

/**
 * TODO separate screen
 *
 * @author ufdada
 */
public final class SystemMessageState extends AbstractPauseAwareState {

    private Main app;
    private Thread renderThread;
    private AppStateManager states;
    private final Element systemMessagesQueue;

    public enum MessageType {
        INFO("info"),
        FIGHT("fight"),
        ALLY("ally"),
        ALLY_MSG("allymsg"),
        PLAYER_EXIT("playerexit"),
        CREATURE("creature");

        private MessageType(String icon) {
            this.icon = icon;
        }

        public String getIcon() {
            return icon;
        }

        private final String icon;
    };

    public SystemMessageState(Element systemMessages, boolean enabled) {
        this.systemMessagesQueue = systemMessages;

        // can be removed if system messages are correctly detached after quiting the game (like going back to main menu)
        this.removeAllMessages();

        super.setEnabled(enabled);
    }

    public SystemMessageState(Element systemMessages) {
        this(systemMessages, true);
    }

    @Override
    public void initialize(final AppStateManager stateManager, final Application app) {
        super.initialize(stateManager, app);
        this.app = (Main) app;
        renderThread = Thread.currentThread();states = stateManager;
    }

    /**
     * Adds a message to the message queue
     *
     * @param type message type
     * @param text message text
     */
    public void addMessage(MessageType type, String text) {
        if (!this.isEnabled() || !isInitialized()) {
            return;
        }
        requireOwner();
        addMessageIcon(type, text);
        SoundHandle soundHandler = NiftyUtils.getSoundHandler(app.getNifty(),
                GlobalCategory.GUI_MESSAGE_BAR, GlobalType.GUI_MESSAGE_BAR_SLIDE.getId());
        if (soundHandler != null) soundHandler.play();
    }

    public void addMessageIcon(MessageType type, String text) {
        requireOwner();
        createMessage(new MessageState.Message(type, text, true, 0));
    }

    private SystemMessageControl createMessage(MessageState.Message saved) {
        MessageType type = saved.type();String text = saved.text();
        final String icon = String.format("Textures/GUI/Tabs/Messages/mt-%s-$index.png", type.getIcon());
        final String normalIcon = AssetUtils.getCanonicalAssetKey(icon.replace("$index", "00"));
        final String hoverIcon = AssetUtils.getCanonicalAssetKey(icon.replace("$index", "01"));
        final String activeIcon = AssetUtils.getCanonicalAssetKey(icon.replace("$index", "02"));

        Element systemMessage = new ControlBuilder("sysmessage-" + NiftyIdCreator.generate(), "systemMessage") {
            {
                parameter("messageType", type.name());
                parameter("image", normalIcon);
                parameter("hoverImage", hoverIcon);
                parameter("activeImage", activeIcon);
                set("text", text);
            }
        }.build(systemMessagesQueue);
        var control = systemMessage.getControl(SystemMessageControl.class);
        control.restoreState(saved);systemMessage.show();return control;
    }

    @Override
    public void update(float tpf) {
        if (systemMessagesQueue == null) {
            return;
        }

        for (var control : controls()) control.advanceTime(tpf);
    }

    private java.util.List<SystemMessageControl> controls() {
        var controls = new java.util.ArrayList<SystemMessageControl>();
        for (Element child : systemMessagesQueue.getChildren()) {
            var control = child.getControl(SystemMessageControl.class);
            if (control != null && !control.isRemoved()) controls.add(control);
        }
        return controls;
    }

    private toniarts.openkeeper.gui.nifty.message.MessageBoxControl messageBox() {
        return app.getNifty().getScreen(toniarts.openkeeper.game.state.IPlayerScreenController.SCREEN_HUD_ID)
                .findControl("messageBox", toniarts.openkeeper.gui.nifty.message.MessageBoxControl.class);
    }

    public MessageState captureState() {
        requireOwner();var controls = controls();
        return new MessageState(isEnabled(), controls.stream().map(SystemMessageControl::captureState).toList(), messageBox().captureState(controls));
    }

    /** Restore contents and selection without notification sounds or marking unread messages read. */
    public void restoreState(MessageState state) {
        requireOwner();java.util.Objects.requireNonNull(state);
        var replacements = new java.util.ArrayList<SystemMessageControl>();
        var previous = controls();
        try {
            for (var message : state.messages()) replacements.add(createMessage(message));
        } catch (RuntimeException failure) {
            replacements.forEach(SystemMessageControl::dismissMessage);throw failure;
        }
        previous.forEach(SystemMessageControl::dismissMessage);
        messageBox().restoreState(state.box(), replacements);
        super.setEnabled(state.enabled());
    }

    private void requireOwner() {
        if (!isInitialized() || Thread.currentThread() != renderThread || states.getState(SystemMessageState.class) != this)
            throw new IllegalStateException("Message state belongs to the active render-thread owner");
    }

    @Override
    public boolean isPauseable() {
        return true;
    }

    /**
     * Removes all existing messages from the queue
     */
    private void removeAllMessages() {
        if (systemMessagesQueue != null && systemMessagesQueue.getChildrenCount() > 0) {
            systemMessagesQueue.getChildren().forEach((child) -> {
                var control = child.getControl(SystemMessageControl.class);
                if (control != null) control.dismissMessage();else child.markForRemoval();
            });
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();
        this.removeAllMessages();
    }
}
