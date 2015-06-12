package com.greenyetilab.tinywheels;

import com.badlogic.gdx.maps.MapObject;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.Manifold;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.PerformanceCounter;
import com.badlogic.gdx.utils.PerformanceCounters;
import com.badlogic.gdx.utils.Sort;

import java.util.Comparator;

/**
 * Contains all the information and objects running in the world
 */
public class GameWorld implements ContactListener, Disposable {
    public enum State {
        RUNNING,
        BROKEN,
        FINISHED
    }

    private static final float TIME_STEP = 1f/60f;
    private static final int VELOCITY_ITERATIONS = 6;
    private static final int POSITION_ITERATIONS = 2;

    private final TheGame mGame;
    private final MapInfo mMapInfo;

    private final World mBox2DWorld;
    private float mTimeAccumulator = 0;

    private Array<BonusPool> mBonusPools = new Array<BonusPool>();

    private final Array<Racer> mRacers = new Array<Racer>();
    private final Array<Racer> mPlayerRacers = new Array<Racer>();
    private State mState = State.RUNNING;

    private Vector2[] mSkidmarks;
    private int mSkidmarksIndex = 0;
    private final Array<GameObject> mActiveGameObjects = new Array<GameObject>();

    private final PerformanceCounter mBox2DPerformanceCounter;
    private final PerformanceCounter mGameObjectPerformanceCounter;

    public GameWorld(TheGame game, MapInfo mapInfo, GameInfo gameInfo, PerformanceCounters performanceCounters) {
        mSkidmarks = new Vector2[GamePlay.instance.maxSkidmarks];
        mGame = game;
        mBox2DWorld = new World(new Vector2(0, 0), true);
        mBox2DWorld.setContactListener(this);
        mMapInfo = mapInfo;

        mBox2DPerformanceCounter = performanceCounters.add("- box2d");
        mGameObjectPerformanceCounter = performanceCounters.add("- g.o");
        setupRacers(gameInfo.playerInfos);
        setupRoadBorders();
        setupBonusSpots();
        setupBonusPools();
    }

    public MapInfo getMapInfo() {
        return mMapInfo;
    }

    public World getBox2DWorld() {
        return mBox2DWorld;
    }

    public Racer getPlayerRacer(int playerId) {
        return mPlayerRacers.get(playerId);
    }

    public Array<Racer> getRacers() {
        return mRacers;
    }

    public Array<BonusPool> getBonusPools() {
        return mBonusPools;
    }

    public Vehicle getPlayerVehicle(int id) {
        return mPlayerRacers.get(id).getVehicle();
    }

    public Vector2[] getSkidmarks() {
        return mSkidmarks;
    }

    public Array<GameObject> getActiveGameObjects() {
        return  mActiveGameObjects;
    }

    public void addGameObject(GameObject object) {
        mActiveGameObjects.add(object);
    }

    public int getPlayerRank() {
        for (int idx = mRacers.size - 1; idx >= 0; --idx) {
            if (mRacers.get(idx) == mPlayerRacers.first()) { // FIXME
                return idx + 1;
            }
        }
        return -1;
    }

    private static Comparator<Racer> sRacerComparator = new Comparator<Racer>() {
        @Override
        public int compare(Racer racer1, Racer racer2) {
            LapPositionComponent c1 = racer1.getLapPositionComponent();
            LapPositionComponent c2 = racer2.getLapPositionComponent();
            if (!c1.hasFinishedRace() && c2.hasFinishedRace()) {
                return 1;
            }
            if (c1.hasFinishedRace() && !c2.hasFinishedRace()) {
                return -1;
            }
            if (c1.getLapCount() < c2.getLapCount()) {
                return 1;
            }
            if (c1.getLapCount() > c2.getLapCount()) {
                return -1;
            }
            float d1 = c1.getLapDistance();
            float d2 = c2.getLapDistance();
            if (d1 < d2) {
                return 1;
            }
            if (d1 > d2) {
                return -1;
            }
            return 0;
        }
    };

    public void act(float delta) {
        mBox2DPerformanceCounter.start();
        // fixed time step
        // max frame time to avoid spiral of death (on slow devices)
        float frameTime = Math.min(delta, 0.25f);
        mTimeAccumulator += frameTime;
        while (mTimeAccumulator >= TIME_STEP) {
            mBox2DWorld.step(TIME_STEP, VELOCITY_ITERATIONS, POSITION_ITERATIONS);
            mTimeAccumulator -= TIME_STEP;
        }
        mBox2DPerformanceCounter.stop();

        mGameObjectPerformanceCounter.start();
        for (int idx = mActiveGameObjects.size - 1; idx >= 0; --idx) {
            GameObject obj = mActiveGameObjects.get(idx);
            obj.act(delta);
            if (obj.isFinished()) {
                mActiveGameObjects.removeIndex(idx);
                if (obj instanceof Disposable) {
                    ((Disposable) obj).dispose();
                }
            }
        }
        mGameObjectPerformanceCounter.stop();

        // Skip finished racers so that they keep the position they had when they crossed the finish
        // line, even if they continue a bit after it
        int fromIndex;
        for (fromIndex = 0; fromIndex < mRacers.size; ++fromIndex) {
            if (!mRacers.get(fromIndex).getLapPositionComponent().hasFinishedRace()) {
                break;
            }
        }
        Sort.instance().sort(mRacers.items, sRacerComparator, fromIndex, mRacers.size);

        boolean allFinished = true;
        for (Racer racer : mPlayerRacers) {
            if (!racer.getLapPositionComponent().hasFinishedRace()) {
                allFinished = false;
                break;
            }
        }
        if (allFinished) {
            setState(State.FINISHED);
        }
    }

    private void setupRacers(Array<GameInfo.PlayerInfo> playerInfos) {
        VehicleCreator creator = new VehicleCreator(mGame.getAssets(), this);
        Assets assets = mGame.getAssets();

        final float startAngle = 90;
        int rank = 1;
        Array<Vector2> positions = mMapInfo.findStartTilePositions();
        positions.reverse();

        Array<VehicleDef> vehicleDefs = assets.vehicleDefs;
        for (Vector2 position : positions) {
            Racer racer;
            if (rank <= playerInfos.size) {
                GameInfo.PlayerInfo playerInfo = playerInfos.get(rank - 1);
                VehicleDef vehicleDef = assets.getVehicleById(playerInfo.vehicleId);
                Vehicle vehicle = creator.create(vehicleDef, position, startAngle);
                racer = new Racer(this, vehicle);
                racer.setPilot(new PlayerPilot(assets, this, racer, playerInfo.inputHandler));
                mPlayerRacers.add(racer);
            } else {
                VehicleDef vehicleDef = vehicleDefs.get((rank - 1) % vehicleDefs.size);
                Vehicle vehicle = creator.create(vehicleDef, position, startAngle);
                racer = new Racer(this, vehicle);
                racer.setPilot(new AIPilot(mMapInfo, racer));
            }
            addGameObject(racer);
            mRacers.add(racer);
            if (rank == GamePlay.instance.racerCount) {
                break;
            }
            ++rank;
        }
    }

    private void setupRoadBorders() {
        for (MapObject object : mMapInfo.getBordersLayer().getObjects()) {
            Body body = Box2DUtils.createStaticBodyForMapObject(mBox2DWorld, object);
            Box2DUtils.setCollisionInfo(body, CollisionCategories.WALL,
                    CollisionCategories.RACER
                    | CollisionCategories.FLAT_OBJECT
                    | CollisionCategories.RACER_BULLET);
            Box2DUtils.setBodyRestitution(body, GamePlay.instance.borderRestitution / 10.0f);
        }
    }

    private void setupBonusSpots() {
        for (Vector2 pos : mMapInfo.findBonusSpotPositions()) {
            BonusSpot spot = new BonusSpot(mGame.getAssets(), this, pos.x, pos.y);
            addGameObject(spot);
        }
    }

    private void setupBonusPools() {
        mBonusPools.add(new GunBonus.Pool(mGame.getAssets(), this));
        mBonusPools.add(new MineBonus.Pool(mGame.getAssets(), this));
    }

    public void addSkidmarkAt(Vector2 position) {
        Vector2 pos = mSkidmarks[mSkidmarksIndex];
        if (pos == null) {
            pos = new Vector2();
            mSkidmarks[mSkidmarksIndex] = pos;
        }
        pos.x = position.x;
        pos.y = position.y;
        mSkidmarksIndex = (mSkidmarksIndex + 1) % mSkidmarks.length;
    }

    @Override
    public void beginContact(Contact contact) {
        Object userA = contact.getFixtureA().getBody().getUserData();
        Object userB = contact.getFixtureB().getBody().getUserData();
        if (userA instanceof Collidable) {
            ((Collidable) userA).beginContact(contact, contact.getFixtureB());
        }
        if (userB instanceof Collidable) {
            ((Collidable) userB).beginContact(contact, contact.getFixtureA());
        }
    }

    @Override
    public void endContact(Contact contact) {
        Object userA = contact.getFixtureA().getBody().getUserData();
        Object userB = contact.getFixtureB().getBody().getUserData();
        if (userA instanceof Collidable) {
            ((Collidable) userA).endContact(contact, contact.getFixtureB());
        }
        if (userB instanceof Collidable) {
            ((Collidable) userB).endContact(contact, contact.getFixtureA());
        }
    }

    @Override
    public void preSolve(Contact contact, Manifold oldManifold) {
        Object userA = contact.getFixtureA().getBody().getUserData();
        Object userB = contact.getFixtureB().getBody().getUserData();
        if (userA instanceof Collidable) {
            ((Collidable) userA).preSolve(contact, contact.getFixtureB(), oldManifold);
        }
        if (userB instanceof Collidable) {
            ((Collidable) userB).preSolve(contact, contact.getFixtureA(), oldManifold);
        }
    }

    @Override
    public void postSolve(Contact contact, ContactImpulse impulse) {
        Object userA = contact.getFixtureA().getBody().getUserData();
        Object userB = contact.getFixtureB().getBody().getUserData();
        if (userA instanceof Collidable) {
            ((Collidable) userA).postSolve(contact, contact.getFixtureB(), impulse);
        }
        if (userB instanceof Collidable) {
            ((Collidable) userB).postSolve(contact, contact.getFixtureA(), impulse);
        }
    }

    public State getState() {
        return mState;
    }

    private void setState(State state) {
        mState = state;
    }

    @Override
    public void dispose() {
        mMapInfo.dispose();
    }
}
