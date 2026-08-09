package com.ae2quickencodingupload;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingPatternMachineData {
    private static final Map<UUID, Deque<NBTTagCompound>> PENDING = new ConcurrentHashMap<>();

    private PendingPatternMachineData() {
    }

    public static void enqueue(EntityPlayerMP player, NBTTagCompound machineData) {
        if (player == null || machineData == null || machineData.hasNoTags()) {
            return;
        }
        Deque<NBTTagCompound> queue = PENDING.get(player.getUniqueID());
        if (queue == null) {
            Deque<NBTTagCompound> created = new ArrayDeque<>();
            Deque<NBTTagCompound> previous = PENDING.putIfAbsent(player.getUniqueID(), created);
            queue = previous == null ? created : previous;
        }
        synchronized (queue) {
            queue.addLast(machineData.copy());
        }
    }

    public static NBTTagCompound take(EntityPlayerMP player) {
        if (player == null) {
            return null;
        }
        Deque<NBTTagCompound> queue = PENDING.get(player.getUniqueID());
        if (queue == null) {
            return null;
        }
        NBTTagCompound result;
        synchronized (queue) {
            result = queue.pollFirst();
            if (queue.isEmpty()) {
                PENDING.remove(player.getUniqueID(), queue);
            }
        }
        return result;
    }
}
