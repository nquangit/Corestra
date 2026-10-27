package dev.nquangit.corestra.integration;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Serializes prerequisite recovery per flow, retaining the lane throughout sleep and retry. */
final class AutoTriggerPrerequisiteGate implements AutoCloseable {
    private final Map<String, Lane> lanes = new HashMap<>();
    private boolean closed;

    /** Runs one prerequisite sequence; unrelated flows keep independent lanes. */
    <T> T run(String flowId, Supplier<T> work) {
        Lane lane = acquire(flowId);
        try {
            return work.get();
        } finally {
            release(flowId, lane);
        }
    }

    private synchronized Lane acquire(String flowId) {
        requireOpen();
        Lane lane = lanes.computeIfAbsent(flowId, ignored -> new Lane());
        Object ticket = new Object();
        lane.waiters.addLast(ticket);
        try {
            while (lane.busy || lane.waiters.peekFirst() != ticket) {
                wait();
                requireOpen();
            }
            requireOpen();
            lane.waiters.removeFirst();
            lane.busy = true;
            return lane;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TriggerInputException("Waiting for prerequisite recovery was interrupted; request was stopped");
        } finally {
            if (lane.waiters.remove(ticket)) {
                removeIdle(flowId, lane);
                notifyAll();
            }
        }
    }

    private synchronized void release(String flowId, Lane lane) {
        lane.busy = false;
        removeIdle(flowId, lane);
        notifyAll();
    }

    private void removeIdle(String flowId, Lane lane) {
        if (!lane.busy && lane.waiters.isEmpty()) lanes.remove(flowId, lane);
    }

    private void requireOpen() {
        if (closed) throw new TriggerInputException("Extension unloaded while waiting for prerequisite recovery");
        if (Thread.currentThread().isInterrupted())
            throw new TriggerInputException("Prerequisite execution interrupted; request was stopped");
    }

    /** Wakes waiting Burp callbacks on unload; no queued prerequisite may start afterwards. */
    @Override public synchronized void close() {
        closed = true;
        notifyAll();
    }

    private static final class Lane {
        private boolean busy;
        private final ArrayDeque<Object> waiters = new ArrayDeque<>();
    }
}
