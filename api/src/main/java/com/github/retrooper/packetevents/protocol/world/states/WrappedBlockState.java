package com.github.retrooper.packetevents.protocol.world.states;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.enums.*;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import com.github.retrooper.packetevents.util.MappingHelper;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * This class is designed to take advantage of modern minecraft versions
 * It has also been designed so that legacy versions can use this system
 * <p>
 * Write your code once, and use it everywhere.  Platform and version agnostic.
 * <p>
 * The mappings for legacy versions (1.12) was generated by setting blocks in the world at the pos id * 2, 255, data * 2
 * and then the world was upgraded to 1.18 and the block was read, dumping it all into a text file.
 * <p>
 * Mappings from modern versions are from ViaVersion, who have a similar (but a bit slower) system.
 */
public class WrappedBlockState {
    private static final WrappedBlockState AIR = new WrappedBlockState(StateTypes.AIR, new EnumMap<>(StateValue.class), 0, (byte) 0);
    private static final Map<Byte, Map<String, WrappedBlockState>> BY_STRING = new HashMap<>();
    private static final Map<Byte, Map<Integer, WrappedBlockState>> BY_ID = new HashMap<>();
    private static final Map<Byte, Map<WrappedBlockState, String>> INTO_STRING = new HashMap<>();
    private static final Map<Byte, Map<WrappedBlockState, Integer>> INTO_ID = new HashMap<>();
    private static final Map<Byte, Map<StateType, WrappedBlockState>> DEFAULT_STATES = new HashMap<>();

    private static final Map<String, String> STRING_UPDATER = new HashMap<>();

    static {
        STRING_UPDATER.put("minecraft:grass_path", "minecraft:dirt_path"); // 1.16 -> 1.17

            loadLegacy();
            for (ClientVersion version : ClientVersion.values()) {
                if (version.isNewerThanOrEquals(ClientVersion.V_1_13)
                && version.isRelease()) {
                    loadModern(version);
                }
            }
    }

    int globalID;
    StateType type;
    EnumMap<StateValue, Object> data = new EnumMap<>(StateValue.class);
    byte mappingsIndex;

    public WrappedBlockState(StateType type, String[] data, int globalID, byte mappingsIndex) {
        this.type = type;
        this.globalID = globalID;
        if (data == null) return;
        for (String s : data) {
            String[] split = s.split("=");
            StateValue value = StateValue.byName(split[0]);
            this.data.put(value, value.getParser().apply(split[1].toUpperCase(Locale.ROOT)));
        }
        this.mappingsIndex = mappingsIndex;
    }

    public WrappedBlockState(StateType type, EnumMap<StateValue, Object> data, int globalID, byte mappingsIndex) {
        this.globalID = globalID;
        this.type = type;
        this.data = data;
        this.mappingsIndex = mappingsIndex;
    }

    @NotNull
    public static WrappedBlockState getByGlobalId(ClientVersion version, int globalID) {
        byte mappingsIndex = getMappingsIndex(version);
        return BY_ID.get(mappingsIndex).getOrDefault(globalID, AIR).clone();
    }

    @NotNull
    public static WrappedBlockState getByString(ClientVersion version, String string) {
        byte mappingsIndex = getMappingsIndex(version);
        return BY_STRING.get(mappingsIndex).getOrDefault(string, AIR).clone();
    }

    @NotNull
    public static WrappedBlockState getDefaultState(ClientVersion version, StateType type) {
        byte mappingsIndex = getMappingsIndex(version);
        WrappedBlockState state = DEFAULT_STATES.get(mappingsIndex).get(type);
        if (state == null) {
            PacketEvents.getAPI().getLogger().warning("Default state for " + type.getName() + " is null. Returning AIR");
            return AIR;
        }
        return state.clone();
    }

    private static byte getMappingsIndex(ClientVersion version) {
        if (version.isOlderThan(ClientVersion.V_1_13)) {
            return 0;
        }
        else if (version.isOlderThanOrEquals(ClientVersion.V_1_13_1)) {
            return 1;
        }
        else if (version.isOlderThanOrEquals(ClientVersion.V_1_13_2)) {
            return 2;
        }
        else if (version.isOlderThanOrEquals(ClientVersion.V_1_14_4)) {
            return 3;
        }
        else if (version.isOlderThanOrEquals(ClientVersion.V_1_15_2)) {
            return 4;
        }
        else if (version.isOlderThanOrEquals(ClientVersion.V_1_16_1)) {
            return 5;
        }
        else if (version.isOlderThanOrEquals(ClientVersion.V_1_16_2)) {
            return 6;
        }
        else {
            return 7;
        }
    }

    private static String getModernJsonPath(ClientVersion version) {
        if (version.isOlderThanOrEquals(ClientVersion.V_1_13_1)) {
            return "1.13";
        } else if (version.isOlderThanOrEquals(ClientVersion.V_1_13_2)) {
            return "1.13.2";
        } else if (version.isOlderThanOrEquals(ClientVersion.V_1_14_4)) {
            return "1.14";
        } else if (version.isOlderThanOrEquals(ClientVersion.V_1_15_2)) {
            return "1.15";
        } else if (version.isOlderThanOrEquals(ClientVersion.V_1_16_1)) {
            return "1.16";
        } else if (version.isOlderThanOrEquals(ClientVersion.V_1_16_2)) {
            return "1.16.2";
        } else {
            return "1.17";
        }
    }

    private static void loadLegacy() {
        String line;
        Map<Integer, WrappedBlockState> stateByIdMap = new HashMap<>();
        Map<WrappedBlockState, Integer> stateToIdMap = new HashMap<>();
        Map<String, WrappedBlockState> stateByStringMap = new HashMap<>();
        Map<WrappedBlockState, String> stateToStringMap = new HashMap<>();
        Map<StateType, WrappedBlockState> stateTypeToBlockStateMap = new HashMap<>();
        try {
            InputStream mappings = WrappedBlockState.class.getClassLoader().getResourceAsStream("assets/mappings/block/legacy_block_mappings.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(mappings));

            boolean isPointEight = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_8) &&
                    PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_9);
            while ((line = reader.readLine()) != null) {
                String[] split = line.split(",");
                int id = Integer.parseInt(split[0]);
                int data = Integer.parseInt(split[1]);
                int combinedID = (id << 4) | data;

                String fullString = line.substring(split[0].length() + split[1].length() + 2);

                int endIndex = split[2].indexOf("[");

                String blockString = split[2].substring(0, endIndex != -1 ? endIndex : split[2].length());

                StateType type = StateTypes.getByName(blockString.replace("minecraft:", ""));

                String[] dataStrings = null;
                if (endIndex != -1) {
                    dataStrings = line.substring(split[0].length() + split[1].length() + 2 + blockString.length() + 1, line.length() - 1).split(",");
                }

                if (type == null) {
                    PacketEvents.getAPI().getLogger().warning("Could not find type for " + blockString);
                }

                WrappedBlockState state = new WrappedBlockState(type, dataStrings, combinedID, (byte)0);

                stateByStringMap.put(fullString, state);
                stateByIdMap.put(combinedID, state);
                stateToStringMap.put(state, fullString);
                stateToIdMap.put(state, combinedID);

                // 1.12's data type system doesn't support "default states" so I guess this works...
                if (!stateTypeToBlockStateMap.containsKey(type)) {
                    stateTypeToBlockStateMap.put(type, state);
                }
            }
        } catch (IOException e) {
            PacketEvents.getAPI().getLogManager().debug("Palette reading failed! Unsupported version?");
            e.printStackTrace();
        }
        BY_ID.put((byte)0, stateByIdMap);
        INTO_ID.put((byte)0, stateToIdMap);
        BY_STRING.put((byte)0, stateByStringMap);
        INTO_STRING.put((byte)0, stateToStringMap);
        DEFAULT_STATES.put((byte)0, stateTypeToBlockStateMap);
    }

    private static void loadModern(ClientVersion version) {
        JsonObject MAPPINGS = MappingHelper.getJSONObject("block/modern_block_mappings");
        byte mappingsIndex = getMappingsIndex(version);
        String modernVersion = getModernJsonPath(PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());
        Map<Integer, WrappedBlockState> stateByIdMap = new HashMap<>();
        Map<WrappedBlockState, Integer> stateToIdMap = new HashMap<>();
        Map<String, WrappedBlockState> stateByStringMap = new HashMap<>();
        Map<WrappedBlockState, String> stateToStringMap = new HashMap<>();
        Map<StateType, WrappedBlockState> stateTypeToBlockStateMap = new HashMap<>();
        if (MAPPINGS.has(modernVersion)) {
            if (BY_ID.containsKey(mappingsIndex)) {
                return;
            }
            JsonObject map = MAPPINGS.getAsJsonObject(modernVersion);
            map.entrySet().forEach(entry -> {
                int id = Integer.parseInt(entry.getKey());

                String fullBlockString = entry.getValue().getAsString();
                boolean isDefault = fullBlockString.startsWith("*");
                fullBlockString = fullBlockString.replace("*", "");
                int index = fullBlockString.indexOf("[");

                String blockString = fullBlockString.substring(0, index == -1 ? fullBlockString.length() : index);
                StateType type = StateTypes.getByName(blockString.replace("minecraft:", ""));

                if (type == null) {
                    // Let's update the state type to a modern version
                    for (Map.Entry<String, String> stringEntry : STRING_UPDATER.entrySet()) {
                        blockString = blockString.replace(stringEntry.getKey(), stringEntry.getValue());
                    }

                    type = StateTypes.getByName(blockString.replace("minecraft:", ""));

                    if (type == null) {
                        PacketEvents.getAPI().getLogger().warning("Unknown block type: " + fullBlockString);
                    }
                }

                String[] data = null;
                if (index != -1) {
                    data = fullBlockString.substring(index + 1, fullBlockString.length() - 1).split(",");
                }

                WrappedBlockState state = new WrappedBlockState(type, data, id, mappingsIndex);

                if (isDefault) {
                    stateTypeToBlockStateMap.put(state.getType(), state);
                }

                stateByStringMap.put(fullBlockString, state);
                stateByIdMap.put(id, state);
                stateToStringMap.put(state, fullBlockString);
                stateToIdMap.put(state, id);
            });
        } else {
            throw new IllegalStateException("Failed to find block palette mappings for the " + modernVersion + " mappings version!");
        }
        BY_ID.put(mappingsIndex, stateByIdMap);
        INTO_ID.put(mappingsIndex, stateToIdMap);
        BY_STRING.put(mappingsIndex, stateByStringMap);
        INTO_STRING.put(mappingsIndex, stateToStringMap);
        DEFAULT_STATES.put(mappingsIndex, stateTypeToBlockStateMap);
    }

    @Override
    public WrappedBlockState clone() {
        return new WrappedBlockState(type, data.clone(), globalID, mappingsIndex);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WrappedBlockState that = (WrappedBlockState) o;
        // Don't check the global ID, it is determined by the other data types
        return type == that.type && data.equals(that.data);
    }

    @Override
    public int hashCode() {
        // Don't hash the global ID, it is determined by the other data types
        return Objects.hash(type, data);
    }

    public StateType getType() {
        return type;
    }

    // Begin all block data types
    public int getAge() {
        return (int) data.get(StateValue.AGE);
    }

    public void setAge(int age) {
        data.put(StateValue.AGE, age);
        checkIsStillValid();
    }

    public boolean isAttached() {
        return (boolean) data.get(StateValue.ATTACHED);
    }

    public void setAttached(boolean attached) {
        data.put(StateValue.ATTACHED, attached);
        checkIsStillValid();
    }

    public Attachment getAttachment() {
        return (Attachment) data.get(StateValue.ATTACHMENT);
    }

    public void setAttachment(Attachment attachment) {
        data.put(StateValue.ATTACHMENT, attachment);
        checkIsStillValid();
    }

    public Axis getAxis() {
        return (Axis) data.get(StateValue.AXIS);
    }

    public void setAxis(Axis axis) {
        data.put(StateValue.AXIS, axis);
        checkIsStillValid();
    }

    public boolean isBerries() {
        return (boolean) data.get(StateValue.BERRIES);
    }

    public void setBerries(boolean berries) {
        data.put(StateValue.BERRIES, berries);
        checkIsStillValid();
    }

    public int getBites() {
        return (int) data.get(StateValue.BITES);
    }

    public void setBites(int bites) {
        data.put(StateValue.BITES, bites);
        checkIsStillValid();
    }

    public boolean isBottom() {
        return (boolean) data.get(StateValue.BOTTOM);
    }

    public void setBottom(boolean bottom) {
        data.put(StateValue.BOTTOM, bottom);
        checkIsStillValid();
    }

    public int getCandles() {
        return (int) data.get(StateValue.CANDLES);
    }

    public void setCandles(int candles) {
        data.put(StateValue.CANDLES, candles);
        checkIsStillValid();
    }

    public int getCharges() {
        return (int) data.get(StateValue.CHARGES);
    }

    public void setCharges(int charges) {
        data.put(StateValue.CHARGES, charges);
        checkIsStillValid();
    }

    public boolean isConditional() {
        return (boolean) data.get(StateValue.CONDITIONAL);
    }

    public void setConditional(boolean conditional) {
        data.put(StateValue.CONDITIONAL, conditional);
        checkIsStillValid();
    }

    public int getDelay() {
        return (int) data.get(StateValue.DELAY);
    }

    public void setDelay(int delay) {
        data.put(StateValue.DELAY, delay);
        checkIsStillValid();
    }

    public boolean isDisarmed() {
        return (boolean) data.get(StateValue.DISARMED);
    }

    public void setDisarmed(boolean disarmed) {
        data.put(StateValue.DISARMED, disarmed);
        checkIsStillValid();
    }

    public int getDistance() {
        return (int) data.get(StateValue.DISTANCE);
    }

    public void setDistance(int distance) {
        data.put(StateValue.DISTANCE, distance);
        checkIsStillValid();
    }

    public boolean isDown() {
        return (boolean) data.get(StateValue.DOWN);
    }

    public void setDown(boolean down) {
        data.put(StateValue.DOWN, down);
        checkIsStillValid();
    }

    public boolean isDrag() {
        return (boolean) data.get(StateValue.DRAG);
    }

    public void setDrag(boolean drag) {
        data.put(StateValue.DRAG, drag);
        checkIsStillValid();
    }

    public int getEggs() {
        return (int) data.get(StateValue.EGGS);
    }

    public void setEggs(int eggs) {
        data.put(StateValue.EGGS, eggs);
        checkIsStillValid();
    }

    public boolean isEnabled() {
        return (boolean) data.get(StateValue.ENABLED);
    }

    public void setEnabled(boolean enabled) {
        data.put(StateValue.ENABLED, enabled);
        checkIsStillValid();
    }

    public boolean isExtended() {
        return (boolean) data.get(StateValue.EXTENDED);
    }

    public void setExtended(boolean extended) {
        data.put(StateValue.EXTENDED, extended);
        checkIsStillValid();
    }

    public boolean isEye() {
        return (boolean) data.get(StateValue.EYE);
    }

    public void setEye(boolean eye) {
        data.put(StateValue.EYE, eye);
        checkIsStillValid();
    }

    public Face getFace() {
        return (Face) data.get(StateValue.FACE);
    }

    public void setFace(Face face) {
        data.put(StateValue.FACE, face);
        checkIsStillValid();
    }

    public BlockFace getFacing() {
        return (BlockFace) data.get(StateValue.FACING);
    }

    public void setFacing(BlockFace facing) {
        data.put(StateValue.FACING, facing);
        checkIsStillValid();
    }

    public Half getHalf() {
        return (Half) data.get(StateValue.HALF);
    }

    public void setHalf(Half half) {
        data.put(StateValue.HALF, half);
        checkIsStillValid();
    }

    public boolean isHanging() {
        return (boolean) data.get(StateValue.HANGING);
    }

    public void setHanging(boolean hanging) {
        data.put(StateValue.HANGING, hanging);
        checkIsStillValid();
    }

    public boolean isHasBook() {
        return (boolean) data.get(StateValue.HAS_BOOK);
    }

    public void setHasBook(boolean hasBook) {
        data.put(StateValue.HAS_BOOK, hasBook);
        checkIsStillValid();
    }

    public boolean isHasBottle0() {
        return (boolean) data.get(StateValue.HAS_BOTTLE_0);
    }

    public void setHasBottle0(boolean hasBottle0) {
        data.put(StateValue.HAS_BOTTLE_0, hasBottle0);
        checkIsStillValid();
    }

    public boolean isHasBottle1() {
        return (boolean) data.get(StateValue.HAS_BOTTLE_1);
    }

    public void setHasBottle1(boolean hasBottle1) {
        data.put(StateValue.HAS_BOTTLE_1, hasBottle1);
        checkIsStillValid();
    }

    public boolean isHasBottle2() {
        return (boolean) data.get(StateValue.HAS_BOTTLE_2);
    }

    public void setHasBottle2(boolean hasBottle2) {
        data.put(StateValue.HAS_BOTTLE_2, hasBottle2);
        checkIsStillValid();
    }

    public boolean isHasRecord() {
        return (boolean) data.get(StateValue.HAS_RECORD);
    }

    public void setHasRecord(boolean hasRecord) {
        data.put(StateValue.HAS_RECORD, hasRecord);
        checkIsStillValid();
    }

    public int getHatch() {
        return (int) data.get(StateValue.HATCH);
    }

    public void setHatch(int hatch) {
        data.put(StateValue.HATCH, hatch);
        checkIsStillValid();
    }

    public Hinge getHinge() {
        return (Hinge) data.get(StateValue.HINGE);
    }

    public void setHinge(Hinge hinge) {
        data.put(StateValue.HINGE, hinge);
        checkIsStillValid();
    }

    public int getHoneyLevel() {
        return (int) data.get(StateValue.HONEY_LEVEL);
    }

    public void setHoneyLevel(int honeyLevel) {
        data.put(StateValue.HONEY_LEVEL, honeyLevel);
        checkIsStillValid();
    }

    public boolean isInWall() {
        return (boolean) data.get(StateValue.IN_WALL);
    }

    public void setInWall(boolean inWall) {
        data.put(StateValue.IN_WALL, inWall);
        checkIsStillValid();
    }

    public Instrument getInstrument() {
        return (Instrument) data.get(StateValue.INSTRUMENT);
    }

    public void setInstrument(Instrument instrument) {
        data.put(StateValue.INSTRUMENT, instrument);
        checkIsStillValid();
    }

    public boolean isInverted() {
        return (boolean) data.get(StateValue.INVERTED);
    }

    public void setInverted(boolean inverted) {
        data.put(StateValue.INVERTED, inverted);
        checkIsStillValid();
    }

    public int getLayers() {
        return (int) data.get(StateValue.LAYERS);
    }

    public void setLayers(int layers) {
        data.put(StateValue.LAYERS, layers);
        checkIsStillValid();
    }

    public Leaves getLeaves() {
        return (Leaves) data.get(StateValue.LEAVES);
    }

    public void setLeaves(Leaves leaves) {
        data.put(StateValue.LEAVES, leaves);
        checkIsStillValid();
    }

    public int getLevel() {
        return (int) data.get(StateValue.LEVEL);
    }

    public void setLevel(int level) {
        data.put(StateValue.LEVEL, level);
        checkIsStillValid();
    }

    public boolean isLit() {
        return (boolean) data.get(StateValue.LIT);
    }

    public void setLit(boolean lit) {
        data.put(StateValue.LIT, lit);
        checkIsStillValid();
    }

    public boolean isLocked() {
        return (boolean) data.get(StateValue.LOCKED);
    }

    public void setLocked(boolean locked) {
        data.put(StateValue.LOCKED, locked);
        checkIsStillValid();
    }

    public Mode getMode() {
        return (Mode) data.get(StateValue.MODE);
    }

    public void setMode(Mode mode) {
        data.put(StateValue.MODE, mode);
        checkIsStillValid();
    }

    public int getMoisture() {
        return (int) data.get(StateValue.MOISTURE);
    }

    public void setMoisture(int moisture) {
        data.put(StateValue.MOISTURE, moisture);
        checkIsStillValid();
    }

    public North getNorth() {
        return (North) data.get(StateValue.NORTH);
    }

    public void setNorth(North north) {
        data.put(StateValue.NORTH, north);
        checkIsStillValid();
    }

    public int getNote() {
        return (int) data.get(StateValue.NOTE);
    }

    public void setNote(int note) {
        data.put(StateValue.NOTE, note);
        checkIsStillValid();
    }

    public boolean isOccupied() {
        return (boolean) data.get(StateValue.OCCUPIED);
    }

    public void setOccupied(boolean occupied) {
        data.put(StateValue.OCCUPIED, occupied);
        checkIsStillValid();
    }

    public boolean isOpen() {
        return (boolean) data.get(StateValue.OPEN);
    }

    public void setOpen(boolean open) {
        data.put(StateValue.OPEN, open);
        checkIsStillValid();
    }

    public Orientation getOrientation() {
        return (Orientation) data.get(StateValue.ORIENTATION);
    }

    public void setOrientation(Orientation orientation) {
        data.put(StateValue.ORIENTATION, orientation);
        checkIsStillValid();
    }

    public Part getPart() {
        return (Part) data.get(StateValue.PART);
    }

    public void setPart(Part part) {
        data.put(StateValue.PART, part);
        checkIsStillValid();
    }

    public boolean isPersistent() {
        return (boolean) data.get(StateValue.PERSISTENT);
    }

    public void setPersistent(boolean persistent) {
        data.put(StateValue.PERSISTENT, persistent);
        checkIsStillValid();
    }

    public int getPickles() {
        return (int) data.get(StateValue.PICKLES);
    }

    public void setPickles(int pickles) {
        data.put(StateValue.PICKLES, pickles);
        checkIsStillValid();
    }

    public int getPower() {
        return (int) data.get(StateValue.POWER);
    }

    public void setPower(int power) {
        data.put(StateValue.POWER, power);
        checkIsStillValid();
    }

    public boolean isPowered() {
        return (boolean) data.get(StateValue.POWERED);
    }

    public void setPowered(boolean powered) {
        data.put(StateValue.POWERED, powered);
        checkIsStillValid();
    }

    public int getRotation() {
        return (int) data.get(StateValue.ROTATION);
    }

    public void setRotation(int rotation) {
        data.put(StateValue.ROTATION, rotation);
        checkIsStillValid();
    }

    public SculkSensorPhase getSculkSensorPhase() {
        return (SculkSensorPhase) data.get(StateValue.SCULK_SENSOR_PHASE);
    }

    public void setSculkSensorPhase(SculkSensorPhase sculkSensorPhase) {
        data.put(StateValue.SCULK_SENSOR_PHASE, sculkSensorPhase);
        checkIsStillValid();
    }

    public Shape getShape() {
        return (Shape) data.get(StateValue.SHAPE);
    }

    public void setShape(Shape shape) {
        data.put(StateValue.SHAPE, shape);
        checkIsStillValid();
    }

    public boolean isShort() {
        return (boolean) data.get(StateValue.SHORT);
    }

    public void setShort(boolean short_) {
        data.put(StateValue.SHORT, short_);
        checkIsStillValid();
    }

    public boolean isSignalFire() {
        return (boolean) data.get(StateValue.SIGNAL_FIRE);
    }

    public void setSignalFire(boolean signalFire) {
        data.put(StateValue.SIGNAL_FIRE, signalFire);
        checkIsStillValid();
    }

    public boolean isSnowy() {
        return (boolean) data.get(StateValue.SNOWY);
    }

    public void setSnowy(boolean snowy) {
        data.put(StateValue.SNOWY, snowy);
        checkIsStillValid();
    }

    public int getStage() {
        return (int) data.get(StateValue.STAGE);
    }

    public void setStage(int stage) {
        data.put(StateValue.STAGE, stage);
        checkIsStillValid();
    }

    public South getSouth() {
        return (South) data.get(StateValue.SOUTH);
    }

    public void setSouth(South south) {
        data.put(StateValue.SOUTH, south);
        checkIsStillValid();
    }

    public Thickness getThickness() {
        return (Thickness) data.get(StateValue.THICKNESS);
    }

    public void setThickness(Thickness thickness) {
        data.put(StateValue.THICKNESS, thickness);
        checkIsStillValid();
    }

    public Tilt getTilt() {
        return (Tilt) data.get(StateValue.TILT);
    }

    public void setTilt(Tilt tilt) {
        data.put(StateValue.TILT, tilt);
        checkIsStillValid();
    }

    public boolean isTriggered() {
        return (boolean) data.get(StateValue.TRIGGERED);
    }

    public void setTriggered(boolean triggered) {
        data.put(StateValue.TRIGGERED, triggered);
        checkIsStillValid();
    }

    public Type getTypeData() {
        return (Type) data.get(StateValue.TYPE);
    }

    public void setTypeData(Type type) {
        data.put(StateValue.TYPE, type);
        checkIsStillValid();
    }

    public boolean isUnstable() {
        return (boolean) data.get(StateValue.UNSTABLE);
    }

    public void setUnstable(boolean unstable) {
        data.put(StateValue.UNSTABLE, unstable);
        checkIsStillValid();
    }

    public boolean isUp() {
        return (boolean) data.get(StateValue.UP);
    }

    public void setUp(boolean up) {
        data.put(StateValue.UP, up);
        checkIsStillValid();
    }

    public VerticalDirection getVerticalDirection() {
        return (VerticalDirection) data.get(StateValue.VERTICAL_DIRECTION);
    }

    public void setVerticalDirection(VerticalDirection verticalDirection) {
        data.put(StateValue.VERTICAL_DIRECTION, verticalDirection);
        checkIsStillValid();
    }

    public boolean isWaterlogged() {
        return (boolean) data.get(StateValue.WATERLOGGED);
    }

    // End all block data types

    public void setWaterlogged(boolean waterlogged) {
        data.put(StateValue.WATERLOGGED, waterlogged);
        checkIsStillValid();
    }

    public East getEast() {
        return (East) data.get(StateValue.EAST);
    }

    public void setEast(East west) {
        data.put(StateValue.EAST, west);
        checkIsStillValid();
    }

    public West getWest() {
        return (West) data.get(StateValue.WEST);
    }

    public void setWest(West west) {
        data.put(StateValue.WEST, west);
        checkIsStillValid();
    }

    /**
     * This checks if the block state is still valid after modifying it
     * If it isn't, then this block will be reverted to the previous state using the global id
     * This is because I believe it's better to revert illegal modification than to simply set to air for doing so
     * As multi-version makes block data still annoying
     */
    public void checkIsStillValid() {
        int oldGlobalID = globalID;
        globalID = getGlobalIdNoCache();
        if (globalID == -1) { // -1 maps to no block as negative ID are impossible
            WrappedBlockState blockState = BY_ID.get(mappingsIndex).getOrDefault(globalID, AIR).clone();
            this.type = blockState.type;
            this.globalID = blockState.globalID;
            this.data = blockState.data.clone();

            // Stack tracing is expensive
            if (PacketEvents.getAPI().getSettings().isDebugEnabled()) {
                PacketEvents.getAPI().getLogManager().warn("Attempt to modify an unknown property for this game version and block!");
                PacketEvents.getAPI().getLogManager().warn("Block: " + type.getName());
                for (Map.Entry<StateValue, Object> entry : data.entrySet()) {
                    PacketEvents.getAPI().getLogManager().warn(entry.getKey() + ": " + entry.getValue());
                }
                new IllegalStateException("An invalid modification was made to a block!").printStackTrace();
            }
        }
    }

    /**
     * This method is helpful if you want to check if a block can be
     * waterlogged, or has other properties.
     * <p>
     * Unless you know what you are doing exactly, don't touch this method!
     * It can result in invalid block types when modified directly
     */
    @Deprecated
    public EnumMap<StateValue, Object> getInternalData() {
        return data;
    }

    /**
     * Global ID
     * For pre-1.13: 4 bits of block data, 4 bits empty, 8 bits block type
     * For post-1.13: Global ID
     *
     * @return
     */
    public int getGlobalId() {
        return globalID;
    }

    /**
     * Internal method for determining if the block state is still valid
     */
    private int getGlobalIdNoCache() {
        return INTO_ID.get(mappingsIndex).getOrDefault(this, -1);
    }

    @Override
    public String toString() {
        return INTO_STRING.get(mappingsIndex).get(this);
    }
}