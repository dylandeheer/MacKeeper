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
package toniarts.openkeeper.view;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.AssetManager;
import com.jme3.input.InputManager;
import com.jme3.light.AmbientLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.Spatial.CullHint;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.ui.Picture;
import com.simsilica.es.EntityData;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.component.ObjectViewState;
import static toniarts.openkeeper.tools.convert.AssetsConverter.TEXTURES_FOLDER;
import toniarts.openkeeper.tools.convert.map.ArtResource;
import toniarts.openkeeper.tools.convert.map.ArtResource.ArtResourceType;
import toniarts.openkeeper.tools.convert.map.Creature;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.utils.AssetUtils;
import toniarts.openkeeper.utils.Utils;
import toniarts.openkeeper.view.animation.AnimationLoader;
import toniarts.openkeeper.view.control.CreatureViewControl;
import toniarts.openkeeper.view.control.IEntityViewControl;
import toniarts.openkeeper.view.control.ObjectViewControl;

/**
 * TODO I think we need to move cursor here
 *
 * @author ArchDemon
 */
public abstract class KeeperHandState extends AbstractAppState {

    private static final List<String> SLAP_SOUNDS = Arrays.asList(new String[]{
        "/Global/GuiHD/Slap_1.mp2",
        "/Global/GuiHD/slap_2.mp2",
        "/Global/GuiHD/Slap_3.mp2",
        "/Global/GuiHD/Slap_4.mp2"
    });
    private static final String MORE_CREATURES = "GUI/Creatures/more_creatures";

    //FIXME: 8 in original but two lines by 4
    private static final int CURSOR_ITEMS_INLINE = 4;
    private static final int CURSOR_ITEMS_LINES = 2;
    private static final int CURSOR_ITEM_SIZE = 32;
    private static final float HAND_CAMERA_DISTANCE = 10_000f;
    private static final float HAND_CAMERA_FAR_PLANE = HAND_CAMERA_DISTANCE * 2f;

    private Main app;
    private AssetManager assetManager;
    private AppStateManager stateManager;
    private InputManager inputManager;
    private final List<KeeperHandItem> queue;
    private final int maxQueueSize;
    private final IKwdFile kwdFile;
    private final EntityData entityData;
    private final short playerId;
    private IEntityViewControl currentItem;
    private final Node queueNode;
    private final Node cursor;
    private final Node rootNode;
    private final Node handRootNode;
    private HandViewQueue handQueue;
    private Thread renderThread;
    private boolean visible;
    private Camera handCamera;
    private ViewPort handViewPort;

    public KeeperHandState(int maxQueueSize, IKwdFile kwdFile, EntityData entityData, short playerId) {
        this.queue = new ArrayList<>(maxQueueSize);
        this.maxQueueSize = maxQueueSize;
        this.kwdFile = kwdFile;
        this.entityData = entityData;
        this.playerId = playerId;

        rootNode = new Node("Keeper hand");
        handRootNode = new Node("Keeper hand models");

        queueNode = new Node("Queue");
        queueNode.setLocalScale(500);
        Quaternion rotation = new Quaternion();
        rotation.fromAngleAxis(-FastMath.QUARTER_PI, new Vector3f(-1, 1, 0));
        queueNode.setLocalRotation(rotation);
        queueNode.addLight(new AmbientLight(ColorRGBA.White));

        cursor = new Node("Cursor");
        cursor.setLocalTranslation(75, 0, 0);

        handRootNode.attachChild(queueNode);
        rootNode.attachChild(cursor);


    }

    @Override
    public void initialize(final AppStateManager stateManager, final Application app) {
        super.initialize(stateManager, app);

        this.app = (Main) app;
        this.assetManager = this.app.getAssetManager();
        this.stateManager = this.app.getStateManager();
        this.inputManager = this.app.getInputManager();
        renderThread = Thread.currentThread();

        this.app.getGuiNode().attachChild(rootNode);
        initializeHandViewPort();

        handQueue = new HandViewQueue(entityData, playerId, maxQueueSize);
        var initial = handQueue.current();publish(prepare(initial), initial);
        setVisible(visible);
    }

    @Override
    public void update(float tpf) {
        var proposed = handQueue.current();
        if (!proposed.equals(handQueue.observed())) {
            publish(prepare(proposed), proposed);updateCursor();
        }

        updateHandCamera();
        handRootNode.updateLogicalState(tpf);
        handRootNode.updateGeometricState();
    }

    @Override
    public void cleanup() {
        if (handQueue != null) handQueue.close();
        release(queue);queue.clear();currentItem = null;
        queueNode.detachAllChildren();cursor.detachAllChildren();
        app.getGuiNode().detachChild(rootNode);
        if (handViewPort != null) {
            handViewPort.detachScene(handRootNode);
            app.getRenderManager().removeMainView(handViewPort);
            handViewPort = null;
            handCamera = null;
        }

        super.cleanup();
    }

    public static String getSlapSound() {
        return Utils.getRandomItem(KeeperHandState.SLAP_SOUNDS);
    }

    public IEntityViewControl getItem() {
        return currentItem;
    }

    public HandViewState captureState() {
        requireOwner();return new HandViewState(playerId,maxQueueSize,isEnabled(),visible,handQueue.observed());
    }

    /** Restore observations without consuming query changes, picking up, dropping or changing tools. */
    public void restoreState(HandViewState saved) {
        requireOwner();
        if (saved.playerId()!=playerId || saved.capacity()!=maxQueueSize)
            throw new IllegalArgumentException("Hand view belongs to another player/capacity");
        saved.validateDefinitions(kwdFile);
        var prepared = prepare(saved.items());
        publish(prepared,saved.items());
        setPosition(inputManager.getCursorPosition().x,inputManager.getCursorPosition().y);
        setEnabled(saved.enabled());setVisible(saved.visible());
    }

    private void requireOwner() {
        if (!isInitialized() || renderThread != Thread.currentThread()
                || stateManager.getState(KeeperHandState.class) != this || handQueue == null)
            throw new IllegalStateException("Hand view requires the active render-thread owner");
    }

    public boolean isFull() {
        return queue.size() >= maxQueueSize;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        rootNode.setCullHint(visible ? CullHint.Never : CullHint.Always);
        handRootNode.setCullHint(visible ? CullHint.Never : CullHint.Always);
        if (handViewPort != null) {
            handViewPort.setEnabled(visible);
        }
    }

    public void setPosition(float x, float y) {
        rootNode.setLocalTranslation(x, y, 0);
        handRootNode.setLocalTranslation(x, y, 0);
    }

    private void initializeHandViewPort() {
        Camera guiCamera = app.getGuiViewPort().getCamera();
        handCamera = new Camera(guiCamera.getWidth(), guiCamera.getHeight());
        handCamera.setParallelProjection(true);
        handCamera.setLocation(new Vector3f(0, 0, HAND_CAMERA_DISTANCE));
        handCamera.lookAtDirection(Vector3f.UNIT_Z.negate(), Vector3f.UNIT_Y);
        resizeHandCamera(guiCamera.getWidth(), guiCamera.getHeight());

        // JME collapses the Gui bucket's depth range to zero, which makes the
        // submeshes of held 3-D models overwrite one another in submission
        // order. Render after the dungeon but before the GUI instead. Clearing
        // only depth gives the hand its own depth buffer without erasing color.
        handViewPort = app.getRenderManager().createMainView("Keeper hand 3D", handCamera);
        handViewPort.setClearFlags(false, true, false);
        handViewPort.attachScene(handRootNode);
        handViewPort.setEnabled(false);
    }

    private void updateHandCamera() {
        Camera guiCamera = app.getGuiViewPort().getCamera();
        int width = guiCamera.getWidth();
        int height = guiCamera.getHeight();
        if (handCamera.getWidth() != width || handCamera.getHeight() != height) {
            resizeHandCamera(width, height);
        }
    }

    private void resizeHandCamera(int width, int height) {
        handCamera.resize(width, height, true);
        handCamera.setFrustum(1f, HAND_CAMERA_FAR_PLANE, 0, width, height, 0);
    }

    private Picture getIcon(final ArtResource image) {
        if (image.getType() != ArtResourceType.ALPHA
                && image.getType() != ArtResourceType.ADDITIVE_ALPHA
                && image.getType() != ArtResourceType.SPRITE) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        return getIcon(image.getName());
    }

    private Picture getIcon(String filename) {
        //FIXME if filename is null what todo ?
        final String name = AssetUtils.getCanonicalAssetKey(TEXTURES_FOLDER + File.separator + filename + ".png");

        Texture tex = assetManager.loadTexture(name);

        Picture picture = new Picture("cursor");
        picture.setTexture(assetManager, (Texture2D) tex, true);
        picture.setWidth(CURSOR_ITEM_SIZE);
        picture.setHeight(CURSOR_ITEM_SIZE);

        return picture;
    }

    /** Prepare detached controls, subscriptions, textures and held graphics before replacing the live view. */
    private PreparedHand prepare(List<HandViewState.Item> values) {
        var items = new ArrayList<KeeperHandItem>();var icons = new Node("Hand icons");Node current = null;
        try {
            for (var value : values) items.add(createItem(value));
            for (int i=0;i<Math.min(CURSOR_ITEMS_INLINE*CURSOR_ITEMS_LINES+1,items.size());i++) {
                Spatial child = i==CURSOR_ITEMS_INLINE*CURSOR_ITEMS_LINES ? getIcon(MORE_CREATURES) : items.get(i).picture;
                int y=Math.min(i/CURSOR_ITEMS_INLINE,CURSOR_ITEMS_LINES-1),x=i-y*CURSOR_ITEMS_INLINE;
                child.setLocalTranslation(CURSOR_ITEM_SIZE*x,-CURSOR_ITEM_SIZE*y,0);icons.attachChild(child);
            }
            if (!items.isEmpty()) {
                var control=items.getFirst().item;
                if (control.getInHandMesh()!=null) {
                    current=new Node("CurrentItem");current.addControl(control);
                    current.setLocalRotation(Matrix3f.ZERO);
                    AnimationLoader.playAnimation(current,control.getInHandMesh(),assetManager);
                }
            }
            return new PreparedHand(items,icons,current);
        } catch (RuntimeException failure) {release(items);throw failure;}
    }

    private KeeperHandItem createItem(HandViewState.Item value) {
        IEntityViewControl control = switch(value.type()) {
            case CREATURE -> new CreatureViewControl(value.entityId(),entityData,kwdFile.getCreature(value.definitionId()),
                    Creature.AnimationType.IN_HAND,assetManager,null);
            case OBJECT -> new ObjectViewControl(value.entityId(),entityData,kwdFile.getObject(value.definitionId()),new ObjectViewState(),assetManager,null);
            default -> throw new IllegalArgumentException("Unsupported held view type");
        };
        try {return new KeeperHandItem(control,getIcon(control.getInHandIcon()));}
        catch (RuntimeException failure) {control.cleanup();throw failure;}
    }

    private void publish(PreparedHand prepared,List<HandViewState.Item> values) {
        handQueue.publish(values);
        release(queue);queue.clear();queue.addAll(prepared.items);
        queueNode.detachAllChildren();cursor.detachAllChildren();cursor.attachChild(prepared.icons);
        if (prepared.current!=null) queueNode.attachChild(prepared.current);
        currentItem=queue.isEmpty() ? null : queue.getFirst().item;
    }

    private static void release(List<KeeperHandItem> items) {
        for (var item : items) {
            Spatial spatial=item.item.getSpatial();
            if (spatial!=null) spatial.removeControl(item.item);
            item.item.cleanup();
        }
    }

    protected abstract void updateCursor();

    private record KeeperHandItem(IEntityViewControl item,Picture picture) {}
    private record PreparedHand(List<KeeperHandItem> items,Node icons,Node current) {}
}
