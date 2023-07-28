package com.consdata.kouncil.config;

import static java.lang.String.format;
import static java.util.stream.Collectors.toMap;
import static org.apache.logging.log4j.util.Strings.isNotBlank;

import com.consdata.kouncil.KouncilRuntimeException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.trace.http.HttpTraceRepository;
import org.springframework.boot.actuate.trace.http.InMemoryHttpTraceRepository;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@Data
@ConfigurationProperties(prefix = "kouncil")
public class KouncilConfiguration {

    protected static final String SPECIAL_CHARS = "[^a-zA-Z0-9\\s]";
    private static final String INSTALLATION_ID_FILE = "kouncil_installation_id.txt";

    private static final String HOST_PORT_SEPARATOR = ":";

    @Value("${bootstrapServers:}")
    private List<String> initialBootstrapServers = new ArrayList<>();

    @Value("${schemaRegistryUrl:}")
    private String schemaRegistryUrl;

    private List<ClusterConfig> clusters;

    private Map<String, ClusterConfig> clusterConfig;

    private String installationId;

    /**
     * @return first known broker from given cluster
     */
    public String getServerByClusterId(String clusterId) {
        ClusterConfig server = clusterConfig.get(clusterId);
        if (Objects.isNull(server)) {
            throw new KouncilRuntimeException("Unknown clusterId");
        } else {
            return server
                    .getBrokers()
                    .stream()
                    .findFirst()
                    .map(BrokerConfig::getAddress)
                    .orElseThrow(() -> new KouncilRuntimeException("Broker not found"));
        }
    }

    public KafkaProperties getKafkaProperties(String clusterId) {
        return clusterConfig.get(clusterId).getKafka();
    }

    public Optional<BrokerConfig> getBrokerConfigFromCluster(String clusterId, String host, int port) {
        return clusterConfig
                .get(clusterId)
                .getBrokers()
                .stream()
                .filter(b -> compareHosts(host, b.getHost()) && b.getPort().equals(port))
                .findFirst();
    }

    public String getInstallationId() {
        return installationId;
    }

    /**
     * hosts may be specified either in IP or hostname form, this method allows us to compare them regardless of their form
     */
    private boolean compareHosts(String host1, String host2) {
        try {
            InetAddress host1InetAddress = InetAddress.getByName(host1);
            InetAddress host2InetAddress = InetAddress.getByName(host2);
            return host1InetAddress.getHostAddress().equals(host2InetAddress.getHostAddress());
        } catch (UnknownHostException e) {
            log.warn("Could not compare hosts {} - {}", host1, host2, e);
            return false;
        }
    }

    @PostConstruct
    public void initialize() {
        if (clusters != null) {
            initializeAdvancedConfig();
        } else {
            initializeSimpleConfig();
        }
        generateInstallationId();
        log.info(toString());
    }

    private void generateInstallationId() {
        Path path = Paths.get(INSTALLATION_ID_FILE);
        try {
            if (!Files.exists(path)) {
                installationId = UUID.randomUUID().toString();
                Files.write(path, installationId.getBytes());
            } else {
                installationId = Files.readString(path);
            }
        } catch (IOException e) {
            throw new KouncilRuntimeException("Failed to read installation id file", e);
        }
    }


    private void initializeSimpleConfig() {
        log.info("Using simple Kouncil configuration: bootstrapServers={}, schemaRegistryUrl={}", initialBootstrapServers, schemaRegistryUrl);
        clusterConfig = new HashMap<>();
        for (String initialBootstrapServer : initialBootstrapServers) {
            String clusterId = sanitizeClusterId(initialBootstrapServer);
            if (initialBootstrapServer.contains(HOST_PORT_SEPARATOR)) {
                String[] split = initialBootstrapServer.split(HOST_PORT_SEPARATOR);
                String brokerHost = split[0];
                int brokerPort = Integer.parseInt(split[1]);
                ClusterConfig simpleClusterConfig = ClusterConfig
                        .builder()
                        .name(clusterId)
                        .kafka(new KafkaProperties())
                        .broker(BrokerConfig
                                .builder()
                                .host(brokerHost)
                                .port(brokerPort)
                                .build())
                        .build();

                if (isNotBlank(schemaRegistryUrl)) {
                    simpleClusterConfig.setSchemaRegistry(SchemaRegistryConfig.builder()
                            .url(schemaRegistryUrl)
                            .build());
                }
                this.clusterConfig.put(clusterId, simpleClusterConfig);
            } else {
                throw new KouncilRuntimeException(format("Could not parse bootstrap server %s", initialBootstrapServer));
            }
        }
    }

    private void initializeAdvancedConfig() {
        log.info("Advanced Kouncil configuration present, {}", clusters);
        clusterConfig = clusters.stream()
                .collect(toMap(
                        cluster -> sanitizeClusterId(cluster.getName()),
                        cluster -> cluster
                ));

        log.info("Propagating jmx config values from clusters to brokers");
        clusterConfig.values().forEach(cluster -> {
            if (cluster.getJmxPort() != null) {
                log.info("Propagating JMX port {} from cluster {} to brokers", cluster.getJmxPort(), cluster.getName());
                cluster.getBrokers().forEach(broker -> broker.setJmxPort(cluster.getJmxPort()));
            }
            if (cluster.getJmxUser() != null) {
                log.info("Propagating JMX user {} from cluster {} to brokers", cluster.getJmxUser(), cluster.getName());
                cluster.getBrokers().forEach(broker -> broker.setJmxUser(cluster.getJmxUser()));
            }
            if (cluster.getJmxPassword() != null) {
                log.info("Propagating JMX password from cluster {} to brokers", cluster.getName());
                cluster.getBrokers().forEach(broker -> broker.setJmxPassword(cluster.getJmxPassword()));
            }
        });
    }

    private String sanitizeClusterId(String serverId) {
        return serverId.replaceAll(SPECIAL_CHARS, "_");
    }

    @Bean
    public HttpTraceRepository httpTraceRepository() {
        return new InMemoryHttpTraceRepository();
    }

    @Bean("fixedThreadPool")
    public ExecutorService executor() {
        return Executors.newFixedThreadPool(10);
    }

}
