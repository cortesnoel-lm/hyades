/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.vulnmirror;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.dependencytrack.common.KafkaTopic;
import org.dependencytrack.vulnmirror.datasource.Datasource;
import org.dependencytrack.vulnmirror.datasource.DatasourceMirror;
import org.dependencytrack.vulnmirror.state.StateStoreUpdater;
import org.dependencytrack.vulnmirror.state.StateStores;

import static org.apache.kafka.streams.state.Stores.inMemoryKeyValueStore;
import static org.apache.kafka.streams.state.Stores.keyValueStoreBuilder;
import static org.dependencytrack.kstreams.util.KafkaStreamsUtil.processorNameConsume;
import static org.dependencytrack.kstreams.util.KafkaStreamsUtil.processorNameProduce;

class KafkaStreamsTopologyProducer {

    @Produces
    Topology topology(final Instance<DatasourceMirror> datasourceMirrors) {
        final var streamsBuilder = new StreamsBuilder();

        final KStream<String, String> commandStream = streamsBuilder
                .stream(KafkaTopic.VULNERABILITY_MIRROR_COMMAND.getName(), Consumed
                        .with(Serdes.String(), Serdes.String())
                        .withName(processorNameConsume(KafkaTopic.VULNERABILITY_MIRROR_COMMAND)));

        final KStream<String, byte[]> vulnerabilityStream = streamsBuilder
                .stream(KafkaTopic.NEW_VULNERABILITY.getName(), Consumed
                        .with(Serdes.String(), Serdes.ByteArray())
                        .withName(processorNameConsume(KafkaTopic.NEW_VULNERABILITY)));

        // Handle mirror commands, where the key of the command event refers to the
        // data source that shall be mirrored. Events with invalid data sources will
        // be silently dropped.
        // NOTE: Mirrors are expected to execute their work in separate threads,
        // otherwise they'll block the Kafka Streams thread and cause a re-balance.
        // https://github.com/DependencyTrack/hyades/issues/304
        commandStream
                .filter((key, value) -> StringUtils.trimToNull(key) != null,
                        Named.as("filter_events_with_key"))
                .map((key, value) -> {
                    try {
                        return KeyValue.pair(Datasource.valueOf(key), value);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                }, Named.as("parse_datasource_from_key"))
                .foreach((datasource, value) -> datasourceMirrors.stream()
                                .filter(mirror -> mirror.supportsDatasource(datasource))
                                .findAny()
                                .ifPresent(datasourceMirror -> datasourceMirror.doMirror(value)),
                        Named.as("execute_mirror"));

        // For every successfully mirrored vulnerability, calculate the SHA-256 digest
        // of its serialized BOV.
        vulnerabilityStream
                .mapValues((key, bytes) -> DigestUtils.getSha256Digest().digest(bytes),
                        Named.as("hash_vulnerability_bov"))
                .to(KafkaTopic.VULNERABILITY_DIGEST.getName(), Produced
                        .with(Serdes.String(), Serdes.ByteArray())
                        .withName(processorNameProduce(KafkaTopic.VULNERABILITY_DIGEST)));

        // Materialize the BOV digest stream in a global state store.
        streamsBuilder.addGlobalStore(
                keyValueStoreBuilder(
                        inMemoryKeyValueStore(StateStores.VULNERABILITY_DIGESTS),
                        Serdes.String(), Serdes.ByteArray()
                ),
                KafkaTopic.VULNERABILITY_DIGEST.getName(),
                Consumed.with(Serdes.String(), Serdes.ByteArray())
                        .withName(processorNameConsume(KafkaTopic.VULNERABILITY_DIGEST)),
                () -> new StateStoreUpdater<>(StateStores.VULNERABILITY_DIGESTS));

        // Materialize the mirror state stream in a global state store.
        streamsBuilder.addGlobalStore(
                keyValueStoreBuilder(
                        inMemoryKeyValueStore(StateStores.MIRROR_STATES),
                        Serdes.String(), Serdes.ByteArray()
                ),
                KafkaTopic.VULNERABILITY_MIRROR_STATE.getName(),
                Consumed.with(Serdes.String(), Serdes.ByteArray())
                        .withName(processorNameConsume(KafkaTopic.VULNERABILITY_MIRROR_STATE)),
                () -> new StateStoreUpdater<>(StateStores.MIRROR_STATES));

        return streamsBuilder.build();
    }

}
