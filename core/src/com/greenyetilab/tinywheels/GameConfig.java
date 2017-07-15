package com.greenyetilab.tinywheels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * The game configuration
 */
public class GameConfig {
    interface ChangeListener {
        void change();
    }

    public boolean rotateCamera = true;
    public boolean debugEnabled = false;
    public boolean drawVelocities = false;
    public boolean drawTileCorners = false;
    public String input;
    public String onePlayerVehicle;
    public String twoPlayersVehicle1;
    public String twoPlayersVehicle2;

    private Preferences mPreferences;
    private ArrayList<WeakReference<ChangeListener>> mListeners = new ArrayList<WeakReference<ChangeListener>>();

    GameConfig() {
        mPreferences = Gdx.app.getPreferences("com.greenyetilab.tinywheels");
        rotateCamera = mPreferences.getBoolean(PrefConstants.ROTATE_SCREEN_ID, true);

        debugEnabled = mPreferences.getBoolean("debug/box2d", false);
        drawTileCorners = mPreferences.getBoolean("debug/tiles/drawCorners", false);
        drawVelocities = mPreferences.getBoolean("debug/box2d/drawVelocities", false);
        input = mPreferences.getString(PrefConstants.INPUT, PrefConstants.INPUT_DEFAULT);
        onePlayerVehicle = mPreferences.getString(PrefConstants.ONEPLAYER_VEHICLE_ID);
        twoPlayersVehicle1 = mPreferences.getString(PrefConstants.MULTIPLAYER_VEHICLE_ID1);
        twoPlayersVehicle2 = mPreferences.getString(PrefConstants.MULTIPLAYER_VEHICLE_ID2);
    }

    public void addListener(ChangeListener listener) {
        mListeners.add(new WeakReference<ChangeListener>(listener));
    }

    // FIXME: Remove
    public Preferences getPreferences() {
        return mPreferences;
    }

    public void flush() {
        mPreferences.putBoolean(PrefConstants.ROTATE_SCREEN_ID, rotateCamera);
        mPreferences.putString(PrefConstants.INPUT, input);
        mPreferences.putString(PrefConstants.ONEPLAYER_VEHICLE_ID, onePlayerVehicle);
        mPreferences.putString(PrefConstants.MULTIPLAYER_VEHICLE_ID1, twoPlayersVehicle1);
        mPreferences.putString(PrefConstants.MULTIPLAYER_VEHICLE_ID2, twoPlayersVehicle2);
        mPreferences.flush();
        for (WeakReference<ChangeListener> listenerRef : mListeners) {
            ChangeListener listener = listenerRef.get();
            if (listener != null) {
                listener.change();
            }
        }
    }
}
