# Axon JPA empty snapshot-tail reproducer

Minimal reproduction for an Axon Framework issue in
`AggregateBasedJpaEventStorageEngine` when an aggregate is loaded from a snapshot at the current
event-stream head.

Tested with:

- Axon Framework 5.4.1 (first observed with 5.4.0)
- Java 21
- Spring Boot 4.1.1
- Hibernate/JPA
- MySQL 8.4 via Testcontainers
- `InMemorySnapshotStore`

## The defect

The test stores aggregate events with sequence numbers `0`, `1`, and `2`, then stores a snapshot
whose `AggregateSequenceNumberPosition` is `3`. There are no events after the snapshot.

When `SnapshotCapableEventStorageEngine` sources the aggregate, the JPA engine queries from
sequence `3` and receives an empty result. Its empty-stream fallback creates an
`AggregateBasedConsistencyMarker` at `-1`, so the append sequencer returns `0` instead of `3`.
Appending the next event consequently violates the unique aggregate/sequence constraint because
sequence `0` already exists.

The snapshot implementation is deliberately in-memory: the defect is in the JPA event-storage
engine's handling of an empty source, not in snapshot persistence.

## Run it

Prerequisites:

- Java 21
- Docker

```shell
./gradlew test
```

The build stays green while documenting the current behavior:

- one test asserts that Axon 5.4.1 returns sequence `0`;
- one test asserts that the following append fails with MySQL's duplicate-key exception;
- one disabled regression test expresses the expected sequence `3`.

After upgrading to a fixed Axon version, enable the regression test and replace the assertion of
the defective sequence with the expected value.

## Suspected source

In `AggregateBasedJpaEventStorageEngine.aggregateSourceForCriterion`, an empty source uses:

```java
.onComplete(() -> markerReference.compareAndSet(
    null,
    new AggregateBasedConsistencyMarker(aggregateIdentifier, -1)
))
```

For an absolute source beginning at `firstSequenceNumber`, the fallback should preserve the lower
bound, conceptually:

```java
new AggregateBasedConsistencyMarker(
    aggregateIdentifier,
    firstSequenceNumber - 1
)
```

Relevant Axon source:

- [AggregateBasedJpaEventStorageEngine 5.4.1](https://github.com/AxonIQ/AxonFramework/blob/axon-5.4.1/eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/jpa/AggregateBasedJpaEventStorageEngine.java)
- [SnapshotCapableEventStorageEngine 5.4.1](https://github.com/AxonIQ/AxonFramework/blob/axon-5.4.1/eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/snapshotting/SnapshotCapableEventStorageEngine.java)

`META-INF/orm.xml` maps Axon's global index to MySQL `IDENTITY` generation. This matches the
deployment where the issue was observed and is unrelated to the aggregate-sequence defect.
