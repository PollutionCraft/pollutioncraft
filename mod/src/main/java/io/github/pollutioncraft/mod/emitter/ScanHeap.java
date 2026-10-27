package io.github.pollutioncraft.mod.emitter;

import java.util.Arrays;

/**
 * Collects the {@code cap} smallest positions a scan walks past, in a primitive max-heap: a rejected position
 * costs one comparison and an accepted one O(log cap) with no allocation. Order independent, so the result is
 * the same whatever order the walk visits cells in. Each position must be offered once per scan.
 */
final class ScanHeap {
    private final int cap;
    private long[] heap = new long[16];
    private int size;
    /** Positions offered, kept or not. */
    long seen;
    /** Offers that changed the heap: the part of the walk that costs time. */
    long kept;

    ScanHeap(int cap) {
        this.cap = Math.max(1, cap);
    }

    /** @return true if the heap changed (the position was kept), which is the part of an offer that costs time */
    boolean offer(long position) {
        seen++;
        if (size < cap) {
            if (size == heap.length) {
                heap = Arrays.copyOf(heap, Math.min(cap, size * 2));
            }
            heap[size] = position;
            siftUp(size++);
            kept++;
            return true;
        }
        if (position >= heap[0]) {
            return false;
        }
        heap[0] = position;
        siftDown(0);
        kept++;
        return true;
    }

    /** How many positions the heap holds right now. */
    int keptCount() {
        return size;
    }

    /** The kept positions, ascending. */
    long[] sorted() {
        long[] out = Arrays.copyOf(heap, size);
        Arrays.sort(out);
        return out;
    }

    private void siftUp(int index) {
        long value = heap[index];
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (heap[parent] >= value) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = value;
    }

    private void siftDown(int index) {
        long value = heap[index];
        int half = size >>> 1;
        while (index < half) {
            int child = 2 * index + 1;
            if (child + 1 < size && heap[child + 1] > heap[child]) {
                child++;
            }
            if (heap[child] <= value) {
                break;
            }
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = value;
    }
}
