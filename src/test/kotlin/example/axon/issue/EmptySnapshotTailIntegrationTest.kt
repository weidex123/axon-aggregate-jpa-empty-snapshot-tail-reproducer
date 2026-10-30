package example.axon.issue

import jakarta.persistence.EntityManagerFactory
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.axonframework.conversion.jackson.JacksonConverter
import org.axonframework.eventsourcing.eventstore.AggregateBasedConsistencyMarker
import org.axonframework.eventsourcing.eventstore.AggregateSequenceNumberPosition
import org.axonframework.eventsourcing.eventstore.AppendCondition
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker
import org.axonframework.eventsourcing.eventstore.EventCoordinator
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage
import org.axonframework.eventsourcing.eventstore.SnapshotCapableEventStorageEngine
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.eventsourcing.eventstore.SourcingStrategy
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine
import org.axonframework.eventsourcing.snapshot.api.Snapshot
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.messaging.eventstreaming.Tag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer

@Testcontainers
@SpringBootTest(
    classes = [ReproducerApplication::class],
    properties =
        [
            "spring.main.web-application-type=none",
            "spring.jpa.hibernate.ddl-auto=create",
            "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
        ],
)
class EmptySnapshotTailIntegrationTest {
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var jpaEngine: AggregateBasedJpaEventStorageEngine
    private lateinit var snapshotEngine: SnapshotCapableEventStorageEngine
    private lateinit var snapshotStore: InMemorySnapshotStore

    private val aggregateIdentifier = UUID.randomUUID()
    private val aggregateName = QualifiedName(AGGREGATE_TYPE)
    private val criteria =
        EventCriteria.havingTags(AGGREGATE_TYPE, aggregateIdentifier.toString())

    @BeforeEach
    fun setUp() {
        val jacksonConverter = JacksonConverter()
        val eventConverter = DelegatingEventConverter(jacksonConverter)
        jpaEngine =
            AggregateBasedJpaEventStorageEngine(
                JpaTransactionalExecutorProvider(entityManagerFactory),
                eventConverter,
            ) { configuration ->
                configuration.eventCoordinator(EventCoordinator.SIMPLE).batchSize(100)
            }
        snapshotStore = InMemorySnapshotStore(jacksonConverter)
        snapshotEngine = SnapshotCapableEventStorageEngine(jpaEngine, snapshotStore)

        appendInitialEvents()
        snapshotStore
            .store(
                aggregateName,
                aggregateIdentifier,
                Snapshot(
                    AggregateSequenceNumberPosition(NEXT_SEQUENCE),
                    "1.0",
                    "state-at-the-head",
                    Instant.EPOCH,
                    emptyMap(),
                ),
                null,
            )
            .join()
    }

    @AfterEach
    fun closeEngine() {
        jpaEngine.close()
    }

    @Test
    fun `empty snapshot tail resets the next sequence to zero`() {
        assertThat(storedSequences()).containsExactly(0L, 1L, 2L)

        val marker = sourceFromSnapshotAtHead()
        val actualNextSequence =
            marker.createSequencer().incrementAndGetSequenceOf(aggregateIdentifier.toString())

        // This assertion documents the Axon 5.4.1 defect. The expected sequence is 3.
        assertThat(actualNextSequence).isZero()
    }

    @Test
    fun `append after an empty snapshot tail fails on the existing sequence zero`() {
        val marker = sourceFromSnapshotAtHead()

        val failure = catchThrowable {
                snapshotEngine
                    .appendEvents(
                        AppendCondition.withCriteria(criteria).withMarker(marker),
                        null,
                        listOf(event("event-after-snapshot")),
                    )
                    .join()
            }
        val rootCause = generateSequence(failure) { it.cause }.last()
        assertThat(rootCause)
            .isInstanceOf(java.sql.SQLIntegrityConstraintViolationException::class.java)
        assertThat(rootCause.message).contains("Duplicate entry '$aggregateIdentifier-0'")

        assertThat(storedSequences()).containsExactly(0L, 1L, 2L)
    }

    @Disabled("Fails with Axon 5.4.1; enable after the upstream fix")
    @Test
    fun `empty snapshot tail preserves the next aggregate sequence`() {
        val marker = sourceFromSnapshotAtHead()

        assertThat(
                marker.createSequencer().incrementAndGetSequenceOf(aggregateIdentifier.toString())
            )
            .isEqualTo(NEXT_SEQUENCE)
    }

    private fun appendInitialEvents() {
        val marker = AggregateBasedConsistencyMarker(aggregateIdentifier.toString(), -1)
        jpaEngine
            .appendEvents(
                AppendCondition.withCriteria(criteria).withMarker(marker),
                null,
                List(NEXT_SEQUENCE.toInt()) { sequence -> event("initial-$sequence") },
            )
            .join()
    }

    private fun sourceFromSnapshotAtHead(): AggregateBasedConsistencyMarker {
        val source =
            snapshotEngine.source(
                SourcingCondition.conditionFor(
                    SourcingStrategy.Snapshot(aggregateName, aggregateIdentifier, null),
                    criteria,
                ),
                null,
            )

        return checkNotNull(
            source
                .reduce<ConsistencyMarker>(null) { previous, entry ->
                    entry.getResource(ConsistencyMarker.RESOURCE_KEY) ?: previous
                }
                .join()
        ) as AggregateBasedConsistencyMarker
    }

    private fun event(payload: String) =
        GenericTaggedEventMessage(
            GenericEventMessage(MessageType(EVENT_TYPE, "1.0"), payload),
            setOf(Tag(AGGREGATE_TYPE, aggregateIdentifier.toString())),
        )

    private fun storedSequences(): List<Long> =
        jdbcTemplate
            .queryForList(
                """
                SELECT `aggregateSequenceNumber`
                FROM `AggregateEventEntry`
                WHERE `aggregateIdentifier` = ?
                ORDER BY `aggregateSequenceNumber`
                """
                    .trimIndent(),
                Long::class.java,
                aggregateIdentifier.toString(),
            )
            .map(::checkNotNull)

    companion object {
        private const val AGGREGATE_TYPE = "ExampleAggregate"
        private const val EVENT_TYPE = "example.axon.issue.ExampleEvent"
        private const val NEXT_SEQUENCE = 3L

        @Container @JvmStatic val mysql = MySQLContainer("mysql:8.4")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName)
        }
    }
}
