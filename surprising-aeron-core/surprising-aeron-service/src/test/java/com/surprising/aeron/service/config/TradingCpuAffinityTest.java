package com.surprising.aeron.service.config;

import com.surprising.aeron.service.lane.LaneTopology;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingCpuAffinityTest {
    @Test
    void applicationFileAndJavaConfigurationBothDisableAffinityByDefault() {
        assertThat(new CoreConfiguration().getCpuAffinity().isEnabled()).isFalse();
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("surprising.aeron.cpu-affinity.enabled")).isEqualTo("false");
        assertThat(bind(yaml).getCpuAffinity().isEnabled()).isFalse();
    }

    @Test
    void bindsExplicitSwitchAndRoleCpuIdsFromYaml() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ByteArrayResource("""
                surprising:
                  aeron:
                    cpu-affinity:
                      enabled: true
                      owner-cpu: 2
                      matcher-cpu: 8
                      lane0-cpu: 16
                      lane1-cpu: 32
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var cpus = bind(yaml).getCpuAffinity();
        assertThat(cpus.isEnabled()).isTrue();
        assertThat(new int[]{cpus.getOwnerCpu(), cpus.getMatcherCpu(), cpus.getLane0Cpu(), cpus.getLane1Cpu()})
                .containsExactly(2, 8, 16, 32);
    }

    private static CoreConfiguration bind(YamlPropertiesFactoryBean yaml) {
        var source = new MapConfigurationPropertySource();
        yaml.getObject().forEach((key, value) -> source.put(key.toString(), value));
        var configuration = new CoreConfiguration();
        new Binder(source).bind("surprising.aeron", Bindable.ofInstance(configuration));
        return configuration;
    }

    @Test
    void disabledDoesNotValidateCpuAssignmentsOrRestrictWorkerTopology() {
        var disabled = new TradingCpuAffinity(false, -1, -1, -1, -1);
        disabled.validateConfiguration(LaneTopology.characterization());
        startsAndStopsOwner(disabled);
        try (var owner = disabled.owner(); var matcher = disabled.matcher(7); var lane = disabled.accountLane(7)) {
            assertThat(Thread.currentThread().isAlive()).isTrue();
        }
    }

    @Test
    void validatesDistinctNonNegativeCpuIdsAndTheAllowedMask() {
        int[] cpus = {2, 8, 16, 32};
        TradingCpuAffinity.validateCpuIds(cpus);
        BitSet allowed = new BitSet();
        for (int cpu : cpus) allowed.set(cpu);
        TradingCpuAffinity.validateAllowedCpus(cpus, allowed);
        allowed.clear(16);
        assertThatThrownBy(() -> TradingCpuAffinity.validateAllowedCpus(cpus, allowed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("16");
        assertThatThrownBy(() -> TradingCpuAffinity.validateCpuIds(new int[]{1, 2, 2, 4}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradingCpuAffinity.validateCpuIds(new int[]{-1, 2, 3, 4}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macSkipsEnabledAffinityEvenWithoutValidCpuAssignments() {
        var configured = new TradingCpuAffinity(true, -1, -1, -1, -1);
        configured.validateConfiguration(LaneTopology.characterization());
        startsAndStopsOwner(configured);
        try (var owner = configured.owner(); var matcher = configured.matcher(0);
             var lane0 = configured.accountLane(0); var lane1 = configured.accountLane(1)) {
            assertThat(Thread.currentThread().isAlive()).isTrue();
        }
    }

    private static void startsAndStopsOwner(TradingCpuAffinity configuration) {
        var cluster = (io.aeron.cluster.service.Cluster) java.lang.reflect.Proxy.newProxyInstance(
                io.aeron.cluster.service.Cluster.class.getClassLoader(),
                new Class<?>[]{io.aeron.cluster.service.Cluster.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> io.aeron.cluster.service.Cluster.Role.LEADER;
                    case "memberId" -> 0;
                    case "time", "logPosition" -> 0L;
                    case "idleStrategy" -> org.agrona.concurrent.NoOpIdleStrategy.INSTANCE;
                    default -> throw new AssertionError("unexpected Cluster API " + method.getName());
                });
        var egress = new com.surprising.aeron.service.orchestration.egress.ClusterServiceEgress();
        var loop = new com.surprising.aeron.service.orchestration.TradingOwnerLoop(
                com.surprising.product.api.ProductLine.SPOT, egress, configuration);
        egress.start(cluster);
        try {
            loop.start(cluster, null);
            loop.checkFailure();
        } finally { loop.terminate(); egress.clear(); }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxBindsEachRoleToTheRequestedCpuAndRestoresItsOriginalMask() {
        BitSet original = net.openhft.affinity.Affinity.getAffinity();
        org.junit.jupiter.api.Assumptions.assumeTrue(original.cardinality() >= 4,
                "four allowed CPUs are required for the four trading roles");
        int[] cpus = original.stream().limit(4).toArray();
        var configured = new TradingCpuAffinity(true, cpus[0], cpus[1], cpus[2], cpus[3]);
        configured.validateConfiguration(LaneTopology.productionDefault());
        try {
            for (int role = 0; role < 4; role++) {
                try (var binding = switch (role) {
                    case 0 -> configured.owner();
                    case 1 -> configured.matcher(0);
                    default -> configured.accountLane(role - 2);
                }) {
                    BitSet expected = new BitSet();
                    expected.set(cpus[role]);
                    assertThat(net.openhft.affinity.Affinity.getAffinity()).isEqualTo(expected);
                }
                assertThat(net.openhft.affinity.Affinity.getAffinity()).isEqualTo(original);
            }
        } finally { net.openhft.affinity.Affinity.setAffinity(original); }
    }
}
