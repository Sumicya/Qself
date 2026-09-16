/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

/**
 * Damped harmonic oscillator driven toward a target, integrated with the
 * semi-implicit Euler method. Fixed short sub-steps keep a missed frame from
 * adding energy, and rest is snapped when both offset and velocity fall under
 * the declared thresholds. Duration-based interpolators cannot reproduce the
 * press/drag feel of the glass droplet, hence the physics model.
 */
final class Spring {

    private final float stiffness;
    private final float dampingCoefficient;
    private final float restOffset;
    private final float restVelocityFactor;

    private float position;
    private float speed;
    private float aim;
    private boolean inFlight;

    Spring(float dampingRatio, float stiffness, float threshold, float start) {
        this.stiffness = stiffness;
        // critical damping for this stiffness equals 2*sqrt(k); the ratio scales it
        this.dampingCoefficient = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        this.restOffset = threshold;
        this.restVelocityFactor = threshold * 10f;
        this.position = start;
        this.aim = start;
    }

    float value() {
        return position;
    }

    float target() {
        return aim;
    }

    float velocity() {
        return speed;
    }

    boolean isRunning() {
        return inFlight;
    }

    void animateTo(float newTarget) {
        if (newTarget != aim) {
            aim = newTarget;
            inFlight = true;
        }
    }

    /** Skip the animation: place immediately and deactivate. */
    void snapTo(float value) {
        position = value;
        aim = value;
        speed = 0f;
        inFlight = false;
    }

    /**
     * Advance the oscillator by {@code dt} seconds, internally split into
     * short fixed steps. Returns true while motion continues afterwards.
     */
    boolean update(float dt) {
        if (!inFlight) {
            return false;
        }
        float budget = Math.min(dt, 0.064f);
        final float fixedStep = 1f / 240f;
        while (budget > 0f) {
            float h = Math.min(budget, fixedStep);
            budget -= h;
            float acceleration = -stiffness * (position - aim) - dampingCoefficient * speed;
            speed += acceleration * h;
            position += speed * h;
        }
        if (Math.abs(position - aim) < restOffset && Math.abs(speed) < restVelocityFactor) {
            position = aim;
            speed = 0f;
            inFlight = false;
        }
        return inFlight;
    }
}
