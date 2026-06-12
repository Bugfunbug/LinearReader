package com.bugfunbug.linearreader.minecraftapi;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

public final class ChunkPosCompat {

    private static final Method X_METHOD;
    private static final Method Z_METHOD;
    private static final Field X_FIELD;
    private static final Field Z_FIELD;

    static {
        Method xM = null, zM = null;
        Field xF = null, zF = null;

        // Primary path: if ChunkPos is a Java record, obtain the int-typed component
        // accessors by declaration order (x is index 0, z is index 1).  The accessor
        // is retrieved as a Method object and invoked normally; we never touch the
        // component *name*, so Loom intermediary remapping is irrelevant here.
        RecordComponent[] components = ChunkPos.class.getRecordComponents();
        if (components != null) {
            for (RecordComponent comp : components) {
                if (comp.getType() == int.class) {
                    if (xM == null) {
                        xM = comp.getAccessor();
                    } else if (zM == null) {
                        zM = comp.getAccessor();
                        break;
                    }
                }
            }
        }

        // Fallback: name-based reflection.  Works in the development (non-remapped)
        // environment and on older MC versions whose ChunkPos public int field
        // retains the name "x"/"z" after intermediary mapping.  These helpers pass
        // the name through a variable parameter, so Loom cannot remap the string;
        // the fallback is therefore unreliable in Fabric production for versions
        // where the name was re-assigned by intermediary (e.g. 1.21.11).
        if (xM == null) xM = findNoArgMethod("x");
        if (zM == null) zM = findNoArgMethod("z");
        if (xF == null) xF = findIntField("x");
        if (zF == null) zF = findIntField("z");

        X_METHOD = xM;
        Z_METHOD = zM;
        X_FIELD = xF;
        Z_FIELD = zF;
    }

    private static final Method CONTAINING_METHOD = findContainingMethod();
    private static final Constructor<ChunkPos> BLOCK_POS_CONSTRUCTOR = findBlockPosConstructor();

    private ChunkPosCompat() {}

    public static int x(ChunkPos pos) {
        return coordinate(pos, X_METHOD, X_FIELD, "x");
    }

    public static int z(ChunkPos pos) {
        return coordinate(pos, Z_METHOD, Z_FIELD, "z");
    }

    public static ChunkPos containing(BlockPos pos) {
        try {
            if (CONTAINING_METHOD != null) {
                return (ChunkPos) CONTAINING_METHOD.invoke(null, pos);
            }
            if (BLOCK_POS_CONSTRUCTOR != null) {
                return BLOCK_POS_CONSTRUCTOR.newInstance(pos);
            }
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to create ChunkPos from BlockPos", e);
        }
        throw new IllegalStateException("No compatible ChunkPos BlockPos factory is available");
    }

    private static int coordinate(ChunkPos pos, Method method, Field field, String name) {
        try {
            if (method != null) {
                return (Integer) method.invoke(pos);
            }
            if (field != null) {
                return field.getInt(pos);
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to read ChunkPos." + name, e);
        }
        throw new IllegalStateException("No compatible ChunkPos." + name + " accessor is available");
    }

    private static Method findNoArgMethod(String name) {
        try {
            return ChunkPos.class.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Field findIntField(String name) {
        try {
            Field field = ChunkPos.class.getField(name);
            return field.getType() == int.class ? field : null;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Method findContainingMethod() {
        try {
            return ChunkPos.class.getMethod("containing", BlockPos.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Constructor<ChunkPos> findBlockPosConstructor() {
        try {
            return ChunkPos.class.getConstructor(BlockPos.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
