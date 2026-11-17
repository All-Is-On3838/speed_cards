package com.speedcards.data.repo

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class Point(val x: Float, val y: Float)

/**
 * Small Fruchterman–Reingold layout for the note graph. Deterministic (nodes start on rings by depth),
 * so the same graph always looks the same. Coordinates are centred on (0, 0); the centre node stays put.
 */
object ForceLayout {
    fun layout(graph: NoteGraph, iterations: Int = 250, spacing: Float = 160f): Map<Long, Point> {
        val ids = graph.nodes.map { it.noteId }
        if (ids.isEmpty()) return emptyMap()
        val index = ids.withIndex().associate { it.value to it.index }
        val n = ids.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        // Initial placement: rings by depth, evenly spread.
        graph.nodes.groupBy { it.depth }.forEach { (depth, ring) ->
            ring.forEachIndexed { i, node ->
                val angle = 2 * Math.PI * i / ring.size + depth * 0.5
                val r = depth * spacing
                xs[index.getValue(node.noteId)] = (r * cos(angle)).toFloat()
                ys[index.getValue(node.noteId)] = (r * sin(angle)).toFloat()
            }
        }
        val edges = graph.edges.mapNotNull { e -> index[e.from]?.let { a -> index[e.to]?.let { b -> a to b } } }
        val center = index.getValue(graph.centerId)
        val k = spacing
        var temperature = spacing
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        repeat(iterations) {
            dx.fill(0f); dy.fill(0f)
            for (i in 0 until n) for (j in i + 1 until n) {
                val vx = xs[i] - xs[j]
                val vy = ys[i] - ys[j]
                val d = max(sqrt(vx * vx + vy * vy), 0.01f)
                val force = k * k / d
                dx[i] += vx / d * force; dy[i] += vy / d * force
                dx[j] -= vx / d * force; dy[j] -= vy / d * force
            }
            for ((a, b) in edges) {
                val vx = xs[a] - xs[b]
                val vy = ys[a] - ys[b]
                val d = max(sqrt(vx * vx + vy * vy), 0.01f)
                val force = d * d / k
                dx[a] -= vx / d * force; dy[a] -= vy / d * force
                dx[b] += vx / d * force; dy[b] += vy / d * force
            }
            for (i in 0 until n) {
                if (i == center) continue
                val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01f)
                val step = minOf(d, temperature)
                xs[i] += dx[i] / d * step
                ys[i] += dy[i] / d * step
            }
            temperature = max(temperature * 0.97f, 1f)
        }
        val cx = xs[center]
        val cy = ys[center]
        return ids.indices.associate { ids[it] to Point(xs[it] - cx, ys[it] - cy) }
    }
}
