/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.client;

import static io.zeebe.client.util.RecordingGatewayService.broker;
import static io.zeebe.client.util.RecordingGatewayService.partition;
import static io.zeebe.gateway.protocol.GatewayOuterClass.Partition.PartitionBrokerRole.FOLLOWER;
import static io.zeebe.gateway.protocol.GatewayOuterClass.Partition.PartitionBrokerRole.LEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.zeebe.client.api.command.ClientException;
import io.zeebe.client.api.response.BrokerInfo;
import io.zeebe.client.api.response.PartitionBrokerRole;
import io.zeebe.client.api.response.PartitionInfo;
import io.zeebe.client.api.response.Topology;
import io.zeebe.client.util.ClientTest;
import io.zeebe.gateway.protocol.GatewayOuterClass.TopologyRequest;
import java.time.Duration;
import java.util.List;
import org.junit.Test;

public final class TopologyRequestTest extends ClientTest {

  @Test
  public void shouldRequestTopology() {
    // given
    gatewayService.onTopologyRequest(
        2,
        10,
        3,
        "1.22.3-SNAPSHOT",
        broker(0, "host1", 123, partition(0, LEADER), partition(1, FOLLOWER)),
        broker(1, "host2", 212, partition(0, FOLLOWER), partition(1, LEADER)),
        broker(2, "host3", 432, partition(0, FOLLOWER), partition(1, FOLLOWER)));

    // when
    final Topology topology = client.newTopologyRequest().send().join();

    // then
    assertThat(topology.getClusterSize()).isEqualTo(2);
    assertThat(topology.getPartitionsCount()).isEqualTo(10);
    assertThat(topology.getReplicationFactor()).isEqualTo(3);
    assertThat(topology.getGatewayVersion()).isEqualTo("1.22.3-SNAPSHOT");

    final List<BrokerInfo> brokers = topology.getBrokers();
    assertThat(brokers).hasSize(3);

    BrokerInfo broker = brokers.get(0);
    assertThat(broker.getNodeId()).isEqualTo(0);
    assertThat(broker.getHost()).isEqualTo("host1");
    assertThat(broker.getPort()).isEqualTo(123);
    assertThat(broker.getAddress()).isEqualTo("host1:123");
    assertThat(broker.getPartitions())
        .extracting(PartitionInfo::getPartitionId, PartitionInfo::getRole)
        .containsOnly(tuple(0, PartitionBrokerRole.LEADER), tuple(1, PartitionBrokerRole.FOLLOWER));

    broker = brokers.get(1);
    assertThat(broker.getNodeId()).isEqualTo(1);
    assertThat(broker.getHost()).isEqualTo("host2");
    assertThat(broker.getPort()).isEqualTo(212);
    assertThat(broker.getAddress()).isEqualTo("host2:212");
    assertThat(broker.getPartitions())
        .extracting(PartitionInfo::getPartitionId, PartitionInfo::getRole)
        .containsOnly(tuple(0, PartitionBrokerRole.FOLLOWER), tuple(1, PartitionBrokerRole.LEADER));

    broker = brokers.get(2);
    assertThat(broker.getNodeId()).isEqualTo(2);
    assertThat(broker.getHost()).isEqualTo("host3");
    assertThat(broker.getPort()).isEqualTo(432);
    assertThat(broker.getAddress()).isEqualTo("host3:432");
    assertThat(broker.getPartitions())
        .extracting(PartitionInfo::getPartitionId, PartitionInfo::getRole)
        .containsOnly(
            tuple(0, PartitionBrokerRole.FOLLOWER), tuple(1, PartitionBrokerRole.FOLLOWER));
  }

  @Test
  public void shouldRaiseExceptionOnError() {
    // given
    gatewayService.errorOnRequest(
        TopologyRequest.class, () -> new ClientException("Invalid request"));

    // when
    assertThatThrownBy(() -> client.newTopologyRequest().send().join())
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("Invalid request");

    rule.verifyDefaultRequestTimeout();
  }

  @Test
  public void shouldSetRequestTimeout() {
    // given
    final Duration requestTimeout = Duration.ofHours(124);

    // when
    client.newTopologyRequest().requestTimeout(requestTimeout).send().join();

    // then
    rule.verifyRequestTimeout(requestTimeout);
  }
}
