package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.SpringSolver
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * Second-order spring (iOS `AutocompleteSpring`) that keeps position and velocity across retargets,
 * so interrupted motion never jumps. `lead` divides the duration so one axis can lead another;
 * [copy] gives an independent value.
 */
data class AutocompleteSpring(
    var x: Double = 0.0,
    var v: Double = 0.0,
    var t: Double = x,
    // At rest; each `to` brings its role's duration and bounce.
    var duration: Double = Motion.layout.curve().duration,
    var bounce: Double = Motion.layout.curve().bounce,
    var lead: Double = 1.0,
) {
    constructor(initial: Double) : this(x = initial, t = initial)

    fun to(target: Double, duration: Double, bounce: Double, lead: Double = 1.0) {
        t = target
        this.duration = duration
        this.bounce = bounce
        this.lead = lead
    }

    fun snap(value: Double) {
        x = value
        t = value
        v = 0.0
    }

    fun isStill(eps: Double = 0.002): Boolean = abs(x - t) < eps && abs(v) < eps * 8

    fun step(dt: Double) {
        val d = max(0.001, duration / max(lead, 0.1))
        if (d <= 0.004) {
            x = t
            v = 0.0
            return
        }
        val omega = 2 * PI / d
        val zeta = max(0.01, if (bounce >= 0) 1 - bounce else 1 / (1 + bounce))
        val (nx, nv) = SpringSolver.evaluate(from = x, to = t, initialVelocity = v, omega = omega, zeta = zeta, elapsed = dt)
        x = nx
        v = nv
    }
}
