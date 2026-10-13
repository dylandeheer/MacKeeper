/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.random;

import java.util.random.RandomGenerator;

/** World-owned SplitMix64 stream. Capture only inside the world's simulation boundary.
 * The transition and used numeric conversions are explicit parts of save format v1. */
public final class SimulationRandom implements RandomGenerator {
    public record State(long value) { }
    private long state;
    public SimulationRandom() {this(new java.security.SecureRandom().nextLong());}
    public SimulationRandom(long seed) {state=seed;}
    public static SimulationRandom restore(State saved) {return new SimulationRandom(saved.value());}
    public synchronized State captureState() {return new State(state);}
    @Override public synchronized long nextLong() {
        long z=(state += 0x9e3779b97f4a7c15L);
        z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;
        z=(z^(z>>>27))*0x94d049bb133111ebL;
        return z^(z>>>31);
    }
    @Override public int nextInt() {return (int)(nextLong()>>>32);}
    @Override public synchronized int nextInt(int bound) {
        if(bound<=0)throw new IllegalArgumentException("Non-positive random bound");
        int bits=nextInt()>>>1, mask=bound-1;
        if((bound&mask)==0)return bits&mask;
        int result=bits%bound;
        while(bits-result+mask<0){bits=nextInt()>>>1;result=bits%bound;}
        return result;
    }
    @Override public double nextDouble() {return (nextLong()>>>11)*0x1.0p-53;}
    @Override public float nextFloat() {return (nextLong()>>>40)*0x1.0p-24f;}
    @Override public float nextFloat(float bound) {
        if(!(bound>0) || !Float.isFinite(bound))throw new IllegalArgumentException("Invalid random bound");
        float value=nextFloat()*bound;return value<bound?value:Math.nextDown(bound);
    }
}
