/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable.MiscType;

/** Persisted server state and its required definition. Client/session state is not yet included. */
public record SimulationArchive(Definition definition, SimulationState simulation) {
    public record Setting(MiscType type, float value, int unknown1, int unknown2) {
        public Setting {
            Objects.requireNonNull(type);
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid saved setting");
        }
    }
    public record Definition(MapSource source, int ticksPerSecond, List<Setting> settings) {
        public Definition {
            Objects.requireNonNull(source);settings = List.copyOf(settings);
            if (ticksPerSecond <= 0 || ticksPerSecond > 1_000_000_000 || settings.size() > MiscType.values().length)
                throw new IllegalArgumentException("Invalid saved definition");
            var types = EnumSet.noneOf(MiscType.class);
            for (var setting : settings) if (!types.add(setting.type())) throw new IllegalArgumentException("Duplicate saved setting");
            settings = settings.stream().sorted(Comparator.comparing(s -> s.type().name())).toList();
        }
        public static Definition capture(IKwdFile map) {
            return new Definition(map.getMapSource(), map.getGameLevel().getTicksPerSec(),
                    map.getVariables().entrySet().stream().map(entry -> {
                        var value = entry.getValue();
                        if (entry.getKey() != value.getVariableId()) throw new IllegalArgumentException("Setting key differs from its value");
                        return new Setting(entry.getKey(), value.getValue(), value.getUnknown1(), value.getUnknown2());
                    }).toList());
        }
    }
    public SimulationArchive {
        Objects.requireNonNull(definition);Objects.requireNonNull(simulation);
        if (!simulation.triggers().keySet().equals(EnumSet.allOf(TriggerRegistrySnapshots.Kind.class)))
            throw new IllegalArgumentException("Missing trigger registry");
        simulation.triggers().forEach((kind, state) -> {
            if (state.kind() != kind) throw new IllegalArgumentException("Mismatched trigger registry");
        });
    }

    /** Reject different installed content/settings before creating an entity store or invoking the service factory. */
    public GameLoopManager restore(IKwdFile map, Function<GameEntityData, GameSessionServerService> services) throws IOException {
        if (!definition.equals(Definition.capture(map))) throw new IOException("Saved game requires different map data or settings");
        return GameLoopManager.restore(map, simulation, services);
    }
}
