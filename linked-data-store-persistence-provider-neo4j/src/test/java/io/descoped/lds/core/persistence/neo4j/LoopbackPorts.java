package io.descoped.lds.core.persistence.neo4j;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.core.CreateContainerCmdModifier;

import java.util.Map;

/**
 * Publishes the ports of every container that Testcontainers starts on 127.0.0.1 only: the engine a test runs
 * on, and the container Testcontainers starts itself to clean up after the tests (Ryuk).
 * <p>
 * Testcontainers publishes a port on every address of the host and lets Docker pick the port. On macOS, Docker
 * Desktop can then be given a port that another program already listens on at 127.0.0.1. A connection to
 * localhost reaches that program and not the container, and the build fails with "Could not connect to Ryuk" or
 * with an engine that never answers. A port that is asked for on 127.0.0.1 is one that nothing else listens on
 * there.
 * <p>
 * Testcontainers finds this class through {@code META-INF/services}, so it holds for every container of the
 * tests of this module. A port that is published on an address the test names is left as it is.
 */
public final class LoopbackPorts implements CreateContainerCmdModifier {

    private static final String LOOPBACK = "127.0.0.1";

    /**
     * Created by Testcontainers.
     */
    public LoopbackPorts() {
    }

    @Override
    public CreateContainerCmd modify(CreateContainerCmd command) {
        HostConfig hostConfig = command.getHostConfig();
        if (hostConfig == null || hostConfig.getPortBindings() == null) {
            return command;
        }
        Ports onLoopback = new Ports();
        for (Map.Entry<ExposedPort, Ports.Binding[]> port : hostConfig.getPortBindings().getBindings().entrySet()) {
            if (port.getValue() == null) {
                continue;
            }
            for (Ports.Binding binding : port.getValue()) {
                boolean namesAnAddress = binding.getHostIp() != null && !binding.getHostIp().isEmpty();
                onLoopback.bind(port.getKey(), namesAnAddress ? binding : new Ports.Binding(LOOPBACK, binding.getHostPortSpec()));
            }
        }
        hostConfig.withPortBindings(onLoopback);
        return command;
    }

    /**
     * Fails when a port of a started container is published on another address than 127.0.0.1: then
     * Testcontainers did not find this class.
     *
     * @param container a container that is started
     */
    static void check(ContainerState container) {
        Map<ExposedPort, Ports.Binding[]> ports = container.getContainerInfo().getNetworkSettings().getPorts().getBindings();
        for (Map.Entry<ExposedPort, Ports.Binding[]> port : ports.entrySet()) {
            if (port.getValue() == null) {
                continue;
            }
            for (Ports.Binding binding : port.getValue()) {
                if (!LOOPBACK.equals(binding.getHostIp())) {
                    throw new IllegalStateException("Port " + port.getKey() + " of " + container.getContainerInfo().getConfig().getImage()
                            + " is published on " + binding.getHostIp() + ", not on " + LOOPBACK
                            + ": is META-INF/services/" + CreateContainerCmdModifier.class.getName() + " among the test resources?");
                }
            }
        }
    }
}
